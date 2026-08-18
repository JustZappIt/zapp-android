// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.repository

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.ChatNotifier
import co.electriccoin.zcash.ui.common.push.ChatNotificationState
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MimeTypes
import co.electriccoin.zcash.ui.screen.chat.model.byPublicKey
import co.electriccoin.zcash.ui.screen.chat.model.normalizeMessagingPublicKey
import co.electriccoin.zcash.ui.screen.chat.model.resolveDisplayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMMessage

@Suppress("TooManyFunctions")
class ChatConversationsRepositoryImpl(
    private val sdk: ZappMessagingSDK,
    private val chatContactsRepository: ChatContactsRepository,
    private val chatNotifier: ChatNotifier,
    private val applicationStateProvider: ApplicationStateProvider,
    private val chatNotificationState: ChatNotificationState,
    standardPreferenceProvider: StandardPreferenceProvider,
) : ChatConversationsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _conversations = MutableStateFlow<List<ChatConversation>?>(null)
    override val conversations: StateFlow<List<ChatConversation>?> = _conversations.asStateFlow()

    override val localPublicKey: StateFlow<String?> =
        sdk.identity
            .map { it?.publicKey }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val isOnline: StateFlow<Boolean> get() = sdk.isOnline
    override val peerCount: StateFlow<Int> get() = sdk.peerCount
    override val dhtHealth: StateFlow<String> get() = sdk.dhtHealth
    override val conversationDeleted: SharedFlow<String> get() = sdk.groupDeleted

    private val activeConversationId = MutableStateFlow<String?>(null)
    private var refreshJob: Job? = null

    // Nullable until the persisted value loads; maybeNotify treats "not yet known" as
    // "don't notify" so a message in the cold-start window can't fire against a setting
    // the user previously turned off.
    private val notificationsEnabled: StateFlow<Boolean?> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_NOTIFICATIONS_ENABLED.observe(standardPreferenceProvider()))
        }.stateIn(scope, SharingStarted.Eagerly, null)

    // Null until loaded; only pushed to the worklet once known so a cold-start
    // window can't emit receipts against a setting the user turned off.
    private val readReceiptsEnabled: StateFlow<Boolean?> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_READ_RECEIPTS_ENABLED.observe(standardPreferenceProvider()))
        }.stateIn(scope, SharingStarted.Eagerly, null)

    // Whether we advertise our online status to peers. Null until loaded; pushed to
    // the worklet once known and re-asserted after identity comes up, mirroring read
    // receipts. Reciprocity (hiding others' dots when we're hidden) is applied in the
    // chat list/room view models.
    private val showOnlineStatus: StateFlow<Boolean?> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_SHOW_ONLINE_STATUS.observe(standardPreferenceProvider()))
        }.stateIn(scope, SharingStarted.Eagerly, null)

    // "Viewing a conversation" must mean the room is open AND the app is on screen.
    // A backgrounded app with a room still on the back stack is not reading it, so it
    // must not auto-send a read receipt, silence its notification, or swallow its
    // unread bump.
    private val isInForeground: StateFlow<Boolean> =
        applicationStateProvider.isInForeground.stateIn(scope, SharingStarted.Eagerly, true)

    init {
        scope.launch { observeIdentityAndRefresh() }
        observeConversationEvents()
        observeReadReceiptsSetting()
        observePresenceVisibleSetting()
        scope.launch { isInForeground.collect(chatNotificationState::setForeground) }
    }

    override suspend fun refresh() {
        runChatCallResult("ChatConversationsRepository: failed to refresh conversations") {
            sdk.refreshConversations()
            sdk.conversations.value.map(ChatConversation::from)
        }.onSuccess { list ->
            _conversations.update { current ->
                list
                    .mergedWithCached(current)
                    .sortedByDescending { it.lastMessageTimestamp ?: 0L }
            }
        }.onFailure {
            // Surface an empty list so subscribers leave the loading state.
            if (_conversations.value == null) _conversations.value = emptyList()
        }
    }

    override fun markConversationRead(conversationId: String) {
        _conversations.update { current ->
            current?.map { c -> if (c.id == conversationId) c.copy(unreadCount = 0) else c }
        }
        // Clear the system notification too, otherwise the launcher's app-icon badge
        // keeps counting it after the user has read the conversation in-app.
        chatNotifier.cancel(conversationId)
        markRead(conversationId)
    }

    override fun setActiveConversation(conversationId: String?) {
        activeConversationId.value = conversationId
        chatNotificationState.setActiveConversation(conversationId)
    }

    // Compare-and-clear so a room tearing down after its successor already claimed the slot
    // cannot release someone else's claim.
    override fun releaseActiveConversation(conversationId: String) {
        if (activeConversationId.compareAndSet(conversationId, null)) {
            chatNotificationState.setActiveConversation(null)
        }
    }

    override fun recordOutgoingMessage(message: ChatMessage) {
        _conversations.update { current ->
            current?.map { conversation ->
                if (conversation.id == message.conversationId) {
                    conversation.withLatestMessage(
                        preview = lastMessagePreview(message.contentType, message.content, message.mediaId),
                        timestamp = message.timestamp,
                    )
                } else {
                    conversation
                }
            }
        }
    }

    private fun markRead(conversationId: String) {
        // Also gate here, not just in the worklet: the worklet optimistically
        // defaults receipts on until the setting is pushed, so without this a
        // receipts-off user could leak one in the cold-start window.
        if (readReceiptsEnabled.value != true) return
        scope.launch {
            runChatCall("ChatConversationsRepository: markRead failed") {
                sdk.markRead(conversationId)
            }
        }
    }

    private fun observeReadReceiptsSetting() {
        scope.launch {
            readReceiptsEnabled.collect { enabled ->
                if (enabled == null) return@collect
                runChatCall("ChatConversationsRepository: set read receipts failed") {
                    sdk.setReadReceiptsEnabled(enabled)
                }
            }
        }
    }

    private fun observePresenceVisibleSetting() {
        scope.launch {
            showOnlineStatus.collect { visible ->
                if (visible == null) return@collect
                runChatCall("ChatConversationsRepository: set presence visibility failed") {
                    sdk.setPresenceVisible(visible)
                }
            }
        }
    }

    override fun renameConversation(conversationId: String, newName: String) {
        _conversations.update { list ->
            list?.map { conv -> if (conv.id == conversationId) conv.copy(displayName = newName) else conv }
        }
    }

    override suspend fun leaveConversation(conversationId: String) {
        runChatCall("ChatConversationsRepository: leave conversation failed") {
            sdk.removeConversation(conversationId)
            forgetConversation(conversationId)
        }
    }

    private fun forgetConversation(conversationId: String) {
        _conversations.update { it?.filter { conv -> conv.id != conversationId } }
    }

    override fun conversation(conversationId: String): Flow<ChatConversation?> =
        conversations.map { list -> list?.firstOrNull { it.id == conversationId } }

    private suspend fun observeIdentityAndRefresh() {
        sdk.identity.collect { id ->
            if (id != null) {
                startConversationRefresh()
                // Re-assert the setting now the worklet is up, in case the first
                // push raced ahead of it on cold start.
                readReceiptsEnabled.value?.let { enabled ->
                    runChatCall("ChatConversationsRepository: set read receipts failed") {
                        sdk.setReadReceiptsEnabled(enabled)
                    }
                }
                showOnlineStatus.value?.let { visible ->
                    runChatCall("ChatConversationsRepository: set presence visibility failed") {
                        sdk.setPresenceVisible(visible)
                    }
                }
            }
        }
    }

    private fun startConversationRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch { refresh() }
    }

    private fun observeConversationEvents() {
        scope.launch {
            sdk.messageReceived.collect { (conversationId, msg) ->
                if (chatContactsRepository.isBlocked(msg.senderId)) return@collect
                val isActivelyViewing =
                    conversationId == activeConversationId.value && isInForeground.value
                maybeNotify(conversationId, msg, isActivelyViewing)
                // A new message landing in the open room is read on arrival.
                if (isActivelyViewing && !msg.isFromMe) markRead(conversationId)
                _conversations.update { current ->
                    current?.map { conv ->
                        if (conv.id == conversationId) {
                            conv
                                .withLatestMessage(
                                    preview = lastMessagePreview(msg.contentType, msg.content, msg.mediaId),
                                    timestamp = msg.timestamp,
                                ).copy(
                                    unreadCount =
                                        when {
                                            msg.isFromMe -> conv.unreadCount
                                            isActivelyViewing -> conv.unreadCount
                                            else -> conv.unreadCount + 1
                                        },
                                )
                        } else {
                            conv
                        }
                    }
                }
            }
        }
        scope.launch {
            sdk.inviteReceived.collect {
                delay(CONVERSATION_RELOAD_DEBOUNCE_MS)
                refresh()
            }
        }
        scope.launch {
            sdk.groupDeleted.collect { conversationId -> forgetConversation(conversationId) }
        }
        scope.launch {
            sdk.groupRenamed.collect { (conversationId, newName) ->
                renameConversation(conversationId, newName)
            }
        }
        scope.launch {
            sdk.memberLeft.collect { (conversationId, peerKey) ->
                _conversations.update { list ->
                    list?.map { conv ->
                        if (conv.id == conversationId) {
                            conv.copy(participantIds = conv.participantIds.filter { it != peerKey })
                        } else {
                            conv
                        }
                    }
                }
            }
        }
        scope.launch {
            sdk.memberAdded.collect { (conversationId, peerKey, _) ->
                _conversations.update { list ->
                    list?.map { conv ->
                        if (conv.id == conversationId && peerKey !in conv.participantIds) {
                            conv.copy(participantIds = conv.participantIds + peerKey)
                        } else {
                            conv
                        }
                    }
                }
            }
        }
    }

    private fun maybeNotify(
        conversationId: String,
        msg: ZMMessage,
        isActivelyViewing: Boolean,
    ) {
        if (msg.isFromMe || isActivelyViewing || notificationsEnabled.value != true) return
        val contactsByPublicKey = chatContactsRepository.contacts.value.byPublicKey()
        val conversationName =
            _conversations.value
                ?.firstOrNull { it.id == conversationId }
                ?.resolveDisplayName(contactsByPublicKey)
        val senderName =
            contactsByPublicKey[msg.senderId.normalizeMessagingPublicKey()]
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: msg.senderName
        chatNotifier.post(
            conversationId = conversationId,
            conversationName = conversationName,
            senderName = senderName,
            content = msg.content,
        )
    }

    /**
     * Per-type placeholder for the list preview when the message body isn't readable text.
     * Known gap: cold-loaded previews come from the JS core, which only distinguishes GIFs
     * ("[GIF]") — file/location cold-load previews stay generic until the conversation model
     * carries a contentType.
     */
    private fun lastMessagePreview(
        contentType: String?,
        content: String,
        mediaId: String?,
    ): String =
        when {
            contentType == MimeTypes.LOCATION -> {
                ChatConversationsRepository.LOCATION_PLACEHOLDER_SENTINEL
            }

            contentType == MimeTypes.ZEC_TRANSACTION -> {
                ChatConversationsRepository.PAYMENT_PLACEHOLDER_SENTINEL
            }

            contentType == MimeTypes.PAYMENT_REQUEST -> {
                ChatConversationsRepository.PAYMENT_REQUEST_PLACEHOLDER_SENTINEL
            }

            content.isNotEmpty() -> {
                content
            }

            contentType?.startsWith(MimeTypes.IMAGE_PREFIX) == true -> {
                ChatConversationsRepository.PHOTO_PLACEHOLDER_SENTINEL
            }

            contentType?.startsWith(MimeTypes.VIDEO_PREFIX) == true -> {
                ChatConversationsRepository.VIDEO_PLACEHOLDER_SENTINEL
            }

            mediaId != null -> {
                ChatConversationsRepository.FILE_PLACEHOLDER_SENTINEL
            }

            else -> {
                ChatConversationsRepository.MEDIA_PLACEHOLDER_SENTINEL
            }
        }

    private fun ChatConversation.withLatestMessage(
        preview: String,
        timestamp: Long,
    ): ChatConversation =
        if (timestamp >= (lastMessageTimestamp ?: Long.MIN_VALUE)) {
            copy(lastMessage = preview, lastMessageTimestamp = timestamp)
        } else {
            this
        }

    companion object {
        private const val CONVERSATION_RELOAD_DEBOUNCE_MS = 500L
    }
}

// The SDK list lags the live event stream, so a refresh only takes over a conversation it actually
// knows more about. Otherwise the cached activity and unread count win, and a refresh landing just
// after a message would drop the bump and resurrect a badge the user already cleared.
internal fun List<ChatConversation>.mergedWithCached(
    cached: List<ChatConversation>?,
): List<ChatConversation> {
    if (cached.isNullOrEmpty()) return this
    val cachedById = cached.associateBy { it.id }
    return map { refreshed ->
        val live = cachedById[refreshed.id]
        if (live == null || (refreshed.lastMessageTimestamp ?: 0L) > (live.lastMessageTimestamp ?: 0L)) {
            refreshed
        } else {
            refreshed.copy(
                lastMessage = live.lastMessage,
                lastMessageTimestamp = live.lastMessageTimestamp,
                unreadCount = live.unreadCount,
            )
        }
    }
}
