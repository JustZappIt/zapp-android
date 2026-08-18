// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.GetChatConnectionDetailsUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatPeerStatusUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.chat.NewConversationArgs
import co.electriccoin.zcash.ui.screen.chat.SupportTicketListArgs
import co.electriccoin.zcash.ui.screen.chat.common.formatRelativeTime
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ConnectionDetailsUi
import co.electriccoin.zcash.ui.screen.chat.model.ConversationType
import co.electriccoin.zcash.ui.screen.chat.model.byPublicKey
import co.electriccoin.zcash.ui.screen.chat.model.resolveDisplayName
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import co.electriccoin.zcash.ui.screen.chat.support.SupportChatConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class ChatListVM(
    private val chatConversationsRepository: ChatConversationsRepository,
    private val chatContactsRepository: ChatContactsRepository,
    private val getChatConnectionDetails: GetChatConnectionDetailsUseCase,
    private val observeChatPeerStatus: ObserveChatPeerStatusUseCase,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val connectionStatus = MutableStateFlow(ChatListConnectionStatus.CONNECTING)
    private val peerCount = MutableStateFlow(0)
    private val dhtHealth = MutableStateFlow(ChatListDhtHealth.HEALTHY)
    private val connectionDetails = MutableStateFlow<ConnectionDetailsUi?>(null)
    private val showNetworkSheet = MutableStateFlow(false)
    private val showTosDialog = MutableStateFlow(false)
    private val leaveTarget = MutableStateFlow<ChatConversation?>(null)

    // conversationId → online peer ids, aggregated from per-peer status events. Tracking the
    // peer set (not a flat conversation set) keeps multi-peer groups correct: the row is "online"
    // while any peer is connected. Events are replay-0, so rows may read offline until the first
    // status event after subscribing — accepted limitation.
    private val onlinePeersByConversation = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    // Defaults visible until the persisted value loads; gated for reciprocity below.
    private val showOnlineStatus: StateFlow<Boolean> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_SHOW_ONLINE_STATUS.observe(standardPreferenceProvider()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = true,
        )

    init {
        observeConnection()
        observePeerStatus()
        viewModelScope.launch { checkTosAccepted() }
    }

    // The list being on screen means no room is, so assert it rather than trusting every room
    // teardown path to have fired: a stale claim silently swallows unread bumps and leaks receipts.
    fun onScreenVisible() {
        chatConversationsRepository.setActiveConversation(null)
    }

    private fun buildSupportRow(supportConvs: List<ChatConversation>): ChatListSupportRowState {
        val latestSupportMsg =
            supportConvs
                .maxByOrNull { it.lastMessageTimestamp ?: 0L }
                ?.lastMessage
                ?.removePrefix(SupportChatConstants.BOT_PREFIX)
        val subtitle =
            when {
                latestSupportMsg != null -> stringRes(latestSupportMsg)
                supportConvs.isNotEmpty() -> stringRes(R.string.chat_list_support_tickets_fmt, supportConvs.size)
                else -> stringRes(R.string.chat_list_support_subtitle_default)
            }
        return ChatListSupportRowState(
            subtitle = subtitle,
            totalUnreadCount = supportConvs.sumOf { it.unreadCount },
            onClick = ::onSupportClick,
        )
    }

    val state: StateFlow<ChatListState> =
        combine(
            combine(
                chatConversationsRepository.conversations,
                chatContactsRepository.blockedKeys,
                chatConversationsRepository.localPublicKey,
                chatContactsRepository.contacts,
            ) { conversations, blockedKeys, localPublicKey, contacts ->
                ChatSnapshot(conversations, blockedKeys, localPublicKey, contacts)
            },
            combine(connectionStatus, peerCount, dhtHealth) { cs, pc, dh -> Triple(cs, pc, dh) },
            combine(showTosDialog, showNetworkSheet, leaveTarget) { tos, sheet, leave ->
                Triple(tos, sheet, leave)
            },
            connectionDetails,
            // Reciprocity: while our own online status is hidden we surface no peer
            // dots either, so gate the online map by the local setting here.
            combine(onlinePeersByConversation, showOnlineStatus) { peers, show ->
                if (show) peers else emptyMap()
            },
        ) { chat, (cs, pc, dh), (tos, sheet, leave), details, onlinePeers ->
            createState(
                conversations = chat.conversations,
                blockedKeys = chat.blockedKeys,
                localPublicKey = chat.localPublicKey,
                contacts = chat.contacts,
                connectionStatus = cs,
                peerCount = pc,
                dhtHealth = dh,
                connectionDetails = details,
                showNetworkSheet = sheet,
                showTosDialog = tos,
                leaveTarget = leave,
                onlineConversationIds = onlinePeers.filterValues { it.isNotEmpty() }.keys,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    conversations = null,
                    blockedKeys = emptySet(),
                    localPublicKey = null,
                    contacts = emptyList(),
                    connectionStatus = ChatListConnectionStatus.CONNECTING,
                    peerCount = 0,
                    dhtHealth = ChatListDhtHealth.HEALTHY,
                    connectionDetails = null,
                    showNetworkSheet = false,
                    showTosDialog = false,
                    leaveTarget = null,
                    onlineConversationIds = emptySet(),
                ),
        )

    private fun createState(
        conversations: List<ChatConversation>?,
        blockedKeys: Set<String>,
        localPublicKey: String?,
        contacts: List<ChatContact>,
        connectionStatus: ChatListConnectionStatus,
        peerCount: Int,
        dhtHealth: ChatListDhtHealth,
        connectionDetails: ConnectionDetailsUi?,
        showNetworkSheet: Boolean,
        showTosDialog: Boolean,
        leaveTarget: ChatConversation?,
        onlineConversationIds: Set<String>,
    ): ChatListState {
        val contactsByPublicKey = contacts.byPublicKey()
        // Pin the aggregate "Zapp Support" row above the timestamp-sorted list.
        // [isSupportConversation] handles the side-asymmetry: user device requires the
        // support agent's key in participantIds; the support agent's device falls back
        // to the displayName prefix because its own key is excluded from the participant list.
        val supportConvs =
            conversations
                ?.filter { conv ->
                    SupportChatConstants.isSupportConversation(
                        displayName = conv.displayName,
                        participantIds = conv.participantIds,
                        localPublicKey = localPublicKey,
                    )
                }.orEmpty()

        val visibleConversations =
            conversations
                ?.filter { conv ->
                    !SupportChatConstants.isSupportConversation(
                        displayName = conv.displayName,
                        participantIds = conv.participantIds,
                        localPublicKey = localPublicKey,
                    ) && (
                        conv.type != ConversationType.DIRECT ||
                            conv.participantIds.none { it in blockedKeys }
                    )
                }?.sortedByDescending { it.lastMessageTimestamp ?: 0L }
                .orEmpty()

        val supportRow = buildSupportRow(supportConvs)

        return ChatListState(
            title = stringRes(R.string.chat_list_title),
            isLoading = conversations == null,
            items = visibleConversations.map { toItemState(it, contactsByPublicKey, onlineConversationIds) },
            emptyTitle = stringRes(R.string.chat_list_empty_title),
            emptySubtitle = stringRes(R.string.chat_list_empty_subtitle),
            newConversationContentDescription =
                stringRes(R.string.chat_list_new_conversation_content_description),
            onBack = ::onBack,
            onNewConversationClick = ::onNewConversationClick,
            networkChip =
                ChatListNetworkChipState(
                    text = networkChipText(connectionStatus, peerCount),
                    variant = networkChipVariant(connectionStatus),
                    onClick = ::onNetworkChipClick,
                ),
            networkSheet =
                if (showNetworkSheet) {
                    ChatListNetworkSheetState(
                        connectionStatus = connectionStatus,
                        peerCount = peerCount,
                        dhtHealth = dhtHealth,
                        connectionDetails = connectionDetails,
                        onDismiss = ::onNetworkSheetDismiss,
                    )
                } else {
                    null
                },
            tosDialog =
                if (showTosDialog) {
                    ChatListTosDialogState(
                        onAccept = ::onAcceptTos,
                        onDecline = ::onDeclineTos,
                    )
                } else {
                    null
                },
            leaveDialog =
                leaveTarget?.let { conv ->
                    ChatListLeaveDialogState(
                        conversationName = conv.resolveDisplayName(contactsByPublicKey),
                        onConfirm = { onLeaveConfirm(conv) },
                        onDismiss = ::onLeaveDismiss,
                    )
                },
            supportRow = supportRow,
        )
    }

    private fun toItemState(
        conv: ChatConversation,
        contactsByPublicKey: Map<String, ChatContact>,
        onlineConversationIds: Set<String>,
    ): ChatListItemState =
        ChatListItemState(
            id = conv.id,
            displayName = conv.resolveDisplayName(contactsByPublicKey),
            isGroup = conv.type == ConversationType.GROUP,
            isPeerOnline = conv.type == ConversationType.DIRECT && conv.id in onlineConversationIds,
            lastMessage = lastMessageText(conv.lastMessage),
            timeLabel = conv.lastMessageTimestamp?.let { ts -> formatRelativeTime(ts) },
            unreadCount = conv.unreadCount,
            onClick = { onConversationClick(conv) },
            onLeaveSwipe = { onLeaveRequest(conv) },
        )

    private data class ChatSnapshot(
        val conversations: List<ChatConversation>?,
        val blockedKeys: Set<String>,
        val localPublicKey: String?,
        val contacts: List<ChatContact>,
    )

    private fun lastMessageText(value: String?): StringResource =
        when (value) {
            null -> {
                stringRes(R.string.chat_list_no_messages)
            }

            ChatConversationsRepository.MEDIA_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_media_placeholder)
            }

            ChatConversationsRepository.PHOTO_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_photo_placeholder)
            }

            // Cold-load previews come from the JS core, which writes "[GIF]" for GIFs.
            ChatConversationsRepository.GIF_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_gif_placeholder)
            }

            ChatConversationsRepository.VIDEO_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_video_placeholder)
            }

            ChatConversationsRepository.FILE_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_file_placeholder)
            }

            ChatConversationsRepository.LOCATION_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_location_placeholder)
            }

            ChatConversationsRepository.PAYMENT_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_payment_placeholder)
            }

            ChatConversationsRepository.PAYMENT_REQUEST_PLACEHOLDER_SENTINEL -> {
                stringRes(R.string.chat_list_payment_request_placeholder)
            }

            else -> {
                jsonPreview(value) ?: stringRes(value)
            }
        }

    // Cold-loaded previews arrive as the raw JSON body (the live path already maps by contentType).
    // Match a marker on the ~100-char truncation so a payment request/receipt still previews as a label.
    private fun jsonPreview(value: String): StringResource? {
        if (!value.startsWith("{")) return null
        return when {
            isPaymentRequestJsonPreview(value) -> stringRes(R.string.chat_list_payment_request_placeholder)
            value.contains(TRANSACTION_MARKER) -> stringRes(R.string.chat_list_payment_placeholder)
            else -> null
        }
    }

    private fun networkChipText(
        status: ChatListConnectionStatus,
        peerCount: Int,
    ): StringResource =
        when (status) {
            ChatListConnectionStatus.CONNECTED -> stringRes(peerCount.toString())
            ChatListConnectionStatus.CONNECTING -> stringRes(R.string.chat_list_status_connecting)
            ChatListConnectionStatus.DISCONNECTED -> stringRes(R.string.chat_list_status_disconnected)
            ChatListConnectionStatus.ERROR -> stringRes(R.string.chat_list_status_error)
        }

    private fun networkChipVariant(status: ChatListConnectionStatus): ChatListChipVariant =
        when (status) {
            ChatListConnectionStatus.CONNECTED -> ChatListChipVariant.Success

            ChatListConnectionStatus.CONNECTING -> ChatListChipVariant.Accent

            ChatListConnectionStatus.DISCONNECTED,
            ChatListConnectionStatus.ERROR,
            -> ChatListChipVariant.Danger
        }

    private fun onBack() = navigationRouter.back()

    private fun onNewConversationClick() = navigationRouter.forward(NewConversationArgs)

    private fun onSupportClick() = navigationRouter.forward(SupportTicketListArgs)

    private fun onConversationClick(conv: ChatConversation) {
        navigationRouter.forward(ChatRoomArgs(conv.id))
    }

    private fun onLeaveRequest(conv: ChatConversation) {
        leaveTarget.value = conv
    }

    private fun onLeaveDismiss() {
        leaveTarget.value = null
    }

    private fun onLeaveConfirm(conv: ChatConversation) {
        leaveTarget.value = null
        viewModelScope.launch { chatConversationsRepository.leaveConversation(conv.id) }
    }

    private fun onNetworkChipClick() {
        viewModelScope.launch { refreshConnectionDetails() }
        showNetworkSheet.value = true
    }

    private fun onNetworkSheetDismiss() {
        showNetworkSheet.value = false
    }

    private fun onAcceptTos() {
        viewModelScope.launch {
            StandardPreferenceKeys.IS_CHAT_TOS_ACCEPTED
                .putValue(standardPreferenceProvider(), true)
            showTosDialog.value = false
        }
    }

    private fun onDeclineTos() {
        showTosDialog.value = false
    }

    private fun observePeerStatus() {
        viewModelScope.launch {
            observeChatPeerStatus().collect { (conversationId, peerId, status) ->
                onlinePeersByConversation.update { map ->
                    val peers = map[conversationId].orEmpty()
                    val updated = if (status == PEER_STATUS_ONLINE) peers + peerId else peers - peerId
                    if (updated.isEmpty()) map - conversationId else map + (conversationId to updated)
                }
            }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            chatConversationsRepository.isOnline.collect { online ->
                connectionStatus.value =
                    if (online) {
                        ChatListConnectionStatus.CONNECTED
                    } else {
                        ChatListConnectionStatus.DISCONNECTED
                    }
                // A dropped global connection invalidates every per-peer "online" we knew about.
                if (!online) onlinePeersByConversation.value = emptyMap()
            }
        }
        viewModelScope.launch { chatConversationsRepository.peerCount.collect { peerCount.value = it } }
        viewModelScope.launch {
            chatConversationsRepository.dhtHealth.collect { dhtHealth.value = mapDhtHealth(it) }
        }
    }

    private suspend fun refreshConnectionDetails() {
        getChatConnectionDetails()
            .onSuccess { details ->
                connectionDetails.value = ConnectionDetailsUi.from(details)
            }.onFailure {
                connectionDetails.value = null
            }
    }

    private suspend fun checkTosAccepted() {
        val accepted =
            StandardPreferenceKeys.IS_CHAT_TOS_ACCEPTED.getValue(standardPreferenceProvider())
        if (!accepted) showTosDialog.value = true
    }

    companion object {
        private const val PEER_STATUS_ONLINE = "online"
        private const val TRANSACTION_MARKER = "txId"
    }
}

internal fun isPaymentRequestJsonPreview(value: String): Boolean =
    value.startsWith(PAYMENT_REQUEST_PREFIX) || value.contains(PAYMENT_REQUEST_ADDRESS_MARKER)

private const val PAYMENT_REQUEST_PREFIX = "{\"id\":"
private const val PAYMENT_REQUEST_ADDRESS_MARKER = "\"requesterAddress\""
