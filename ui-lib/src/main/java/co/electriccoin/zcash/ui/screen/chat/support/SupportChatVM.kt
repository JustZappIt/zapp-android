// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.chat.media.FileUtils
import co.electriccoin.zcash.ui.screen.chat.media.ImageProcessor
import co.electriccoin.zcash.ui.screen.chat.media.MediaPickEffect
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MimeTypes
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ConversationType as SdkConversationType

@Suppress("TooManyFunctions")
class SupportChatVM(
    args: SupportChatArgs,
    private val application: Application,
    private val sdk: ZappMessagingSDK,
    private val navigationRouter: NavigationRouter,
    private val chatConversationsRepository: ChatConversationsRepository,
) : ViewModel() {
    private val conversationId = MutableStateFlow(args.conversationId.takeIf { it.isNotEmpty() })
    private val messages = MutableStateFlow<List<SupportUiMessage>>(emptyList())
    private val input = MutableStateFlow("")
    private val isLoading = MutableStateFlow(true)
    private val isSubmittingCategory = MutableStateFlow(false)
    private val showLeaveDialog = MutableStateFlow(false)
    private val showMediaSheet = MutableStateFlow(false)

    private val _effects = MutableSharedFlow<MediaPickEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<MediaPickEffect> = _effects.asSharedFlow()

    private var isVisible = false

    init {
        viewModelScope.launch { initialize() }
        subscribeToIncomingMessages()
        markActiveWhenConversationKnown()
    }

    fun onScreenVisible() {
        isVisible = true
        markActive()
    }

    fun onScreenHidden() {
        isVisible = false
        releaseActive()
    }

    override fun onCleared() {
        releaseActive()
        super.onCleared()
    }

    private fun releaseActive() {
        conversationId.value?.let(chatConversationsRepository::releaseActiveConversation)
    }

    // A support ticket is a conversation like any other, so its incoming messages post chat
    // notifications and feed the launcher's app-icon badge. Marking it active while on screen
    // suppresses new notifications, and marking it read clears the badge for messages that
    // arrived before it was opened. Observed (not read once) because a brand-new ticket's id
    // only appears after the category is chosen.
    private fun markActiveWhenConversationKnown() {
        viewModelScope.launch {
            conversationId.collect { if (isVisible) markActive() }
        }
    }

    private fun markActive() {
        val convId = conversationId.value
        chatConversationsRepository.setActiveConversation(convId)
        if (convId != null) chatConversationsRepository.markConversationRead(convId)
    }

    val state: StateFlow<SupportChatScreenState> =
        combine(
            conversationId,
            messages,
            input,
            combine(isLoading, isSubmittingCategory) { l, s -> l to s },
            combine(showLeaveDialog, showMediaSheet) { d, m -> d to m },
        ) { convId, msgs, inp, (loading, submitting), (showLeave, showMedia) ->
            buildState(convId, msgs, inp, loading, submitting, showLeave, showMedia)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                buildState(
                    conversationId = conversationId.value,
                    messages = emptyList(),
                    input = "",
                    isLoading = true,
                    isSubmittingCategory = false,
                    showLeaveDialog = false,
                    showMediaSheet = false,
                ),
        )

    private fun buildState(
        conversationId: String?,
        messages: List<SupportUiMessage>,
        input: String,
        isLoading: Boolean,
        isSubmittingCategory: Boolean,
        showLeaveDialog: Boolean,
        showMediaSheet: Boolean,
    ): SupportChatScreenState {
        val uiState =
            when {
                isLoading -> SupportChatUiState.Loading
                conversationId == null -> SupportChatUiState.SelectCategory(isSubmitting = isSubmittingCategory)
                else -> SupportChatUiState.Chat(messages = messages, input = input)
            }
        return SupportChatScreenState(
            uiState = uiState,
            onCategorySelected = ::onCategorySelected,
            onInputChange = ::onInputChange,
            onSend = ::onSendClick,
            onAttach = ::onAttachClick,
            onMediaCommitted = ::onMediaPicked,
            onLeave = ::onLeaveClick,
            onBack = ::onBack,
            leaveDialog =
                if (showLeaveDialog) {
                    SupportLeaveDialogState(onConfirm = ::onLeaveConfirm, onDismiss = ::onLeaveDismiss)
                } else {
                    null
                },
            mediaSheet =
                if (showMediaSheet) {
                    SupportMediaSheetState(
                        onChooseMedia = ::onChooseMediaClick,
                        onAttachFile = ::onAttachFileClick,
                        onTakePhoto = ::onTakePhotoClick,
                        onDismiss = ::onDismissMediaSheet,
                    )
                } else {
                    null
                },
        )
    }

    private suspend fun initialize() {
        if (conversationId.value != null) loadMessages()
        isLoading.value = false
    }

    private suspend fun loadMessages() {
        val convId = conversationId.value ?: return
        runChatCall("SupportChatVM: loadMessages failed") {
            messages.value =
                sdk
                    .getMessages(convId)
                    .map(ChatMessage::from)
                    .mapNotNull { it.toSupportUiMessageOrNull() }
        }
    }

    private fun subscribeToIncomingMessages() {
        viewModelScope.launch {
            sdk.messageReceived.collect { (incomingConvId, zmMsg) ->
                if (incomingConvId != conversationId.value) return@collect
                val msg = ChatMessage.from(zmMsg).toSupportUiMessageOrNull() ?: return@collect
                messages.update { current ->
                    if (current.any { it.id == msg.id }) current else current + msg
                }
            }
        }
    }

    private fun onCategorySelected(category: SupportCategory) {
        if (conversationId.value != null || isSubmittingCategory.value) return
        isSubmittingCategory.value = true
        viewModelScope.launch {
            runChatCall("SupportChatVM: create ticket failed") {
                val conv =
                    sdk.createConversation(
                        type = SdkConversationType.GROUP,
                        participants = listOf(SupportChatConstants.SUPPORT_PUBLIC_KEY),
                        displayName = "${SupportChatConstants.DISPLAY_NAME_PREFIX}${category.protocolKey}",
                    )
                conversationId.value = conv.id
                sdk.sendMessage(conv.id, SupportChatConstants.categoryMarker(category))
                val greeting = application.getString(category.greetingRes)
                val greetMsg = sdk.sendMessage(conv.id, "${SupportChatConstants.BOT_PREFIX}$greeting")
                ChatMessage.from(greetMsg).toSupportUiMessageOrNull()?.let { ui ->
                    messages.update { it + ui }
                }
            }
            isSubmittingCategory.value = false
        }
    }

    private fun onInputChange(value: String) {
        input.value = value
    }

    private fun onSendClick() {
        val text = input.value.trim()
        val convId = conversationId.value ?: return
        if (text.isEmpty()) return
        input.value = ""
        viewModelScope.launch {
            runChatCall("SupportChatVM: send message failed") {
                val userMsg = sdk.sendMessage(convId, text)
                ChatMessage.from(userMsg).toSupportUiMessageOrNull()?.let { ui ->
                    messages.update { it + ui }
                }
            }
        }
    }

    private fun onAttachClick() {
        showMediaSheet.value = true
    }

    private fun onChooseMediaClick() {
        showMediaSheet.value = false
        _effects.tryEmit(MediaPickEffect.PickMedia)
    }

    private fun onAttachFileClick() {
        showMediaSheet.value = false
        _effects.tryEmit(MediaPickEffect.PickFile)
    }

    private fun onTakePhotoClick() {
        showMediaSheet.value = false
        _effects.tryEmit(MediaPickEffect.TakePhoto)
    }

    private fun onDismissMediaSheet() {
        showMediaSheet.value = false
    }

    fun onMediaPicked(uri: Uri) {
        viewModelScope.launch { sendMediaFromUri(uri) }
    }

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch { sendFileFromUri(uri) }
    }

    fun onCameraCaptured(uri: Uri) {
        viewModelScope.launch { sendCameraCapture(uri) }
    }

    private suspend fun sendMediaFromUri(uri: Uri) {
        val convId = conversationId.value ?: return
        runChatCall("SupportChatVM: sendMedia failed") {
            withContext(Dispatchers.IO) {
                val mimeType = FileUtils.getMimeType(application, uri)
                val thumbnail = thumbnailFor(uri, mimeType)
                when {
                    mimeType == MimeTypes.GIF -> {
                        val cached =
                            FileUtils.copyUriToCache(application, uri)
                                ?: error("Failed to cache GIF")
                        sendMediaMessage(convId, cached.absolutePath, MimeTypes.GIF, thumbnail)
                    }

                    mimeType.startsWith(MimeTypes.IMAGE_PREFIX) -> {
                        val compressed =
                            ImageProcessor.compressImage(application, uri)
                                ?: error("Image compression failed")
                        sendMediaMessage(convId, compressed.absolutePath, MimeTypes.IMAGE_JPEG, thumbnail)
                    }

                    else -> {
                        val cached =
                            FileUtils.copyUriToCache(application, uri)
                                ?: error("Failed to cache media")
                        sendMediaMessage(convId, cached.absolutePath, mimeType, thumbnail)
                    }
                }
            }
        }
    }

    private suspend fun sendFileFromUri(uri: Uri) {
        val convId = conversationId.value ?: return
        runChatCall("SupportChatVM: sendFile failed") {
            withContext(Dispatchers.IO) {
                val cached =
                    FileUtils.copyUriToCache(application, uri)
                        ?: error("Failed to cache file")
                val mimeType = FileUtils.getMimeType(application, uri)
                val thumbnail = thumbnailFor(uri, mimeType)
                sendMediaMessage(convId, cached.absolutePath, mimeType, thumbnail)
            }
        }
    }

    private suspend fun sendCameraCapture(uri: Uri) {
        val convId = conversationId.value ?: return
        runChatCall("SupportChatVM: sendCameraCapture failed") {
            withContext(Dispatchers.IO) {
                val thumbnail = ImageProcessor.generateThumbnail(application, uri)
                val compressed =
                    ImageProcessor.compressImage(application, uri)
                        ?: error("Image compression failed")
                sendMediaMessage(convId, compressed.absolutePath, MimeTypes.IMAGE_JPEG, thumbnail)
            }
        }
    }

    private fun thumbnailFor(uri: Uri, mimeType: String): String? =
        if (mimeType.startsWith(MimeTypes.IMAGE_PREFIX)) {
            ImageProcessor.generateThumbnail(application, uri)
        } else {
            null
        }

    private suspend fun sendMediaMessage(
        convId: String,
        mediaPath: String,
        contentType: String,
        thumbnailData: String?,
    ) {
        runChatCall("SupportChatVM: sendMediaMessage failed") {
            val zmMessage = sdk.sendMediaMessage(convId, mediaPath, contentType, "", thumbnailData)
            ChatMessage.from(zmMessage).toSupportUiMessageOrNull()?.let { ui ->
                messages.update { it + ui }
            }
        }
    }

    private fun onLeaveClick() {
        showLeaveDialog.value = true
    }

    private fun onLeaveDismiss() {
        showLeaveDialog.value = false
    }

    private fun onLeaveConfirm() {
        showLeaveDialog.value = false
        val convId = conversationId.value
        viewModelScope.launch {
            if (convId != null) {
                runChatCall("SupportChatVM: send leave notice failed") {
                    val notice = application.getString(R.string.support_chat_leave_notice)
                    sdk.sendMessage(convId, "${SupportChatConstants.BOT_PREFIX}$notice")
                }
                runChatCall("SupportChatVM: removeConversation failed") {
                    sdk.removeConversation(convId)
                }
            }
            navigationRouter.back()
        }
    }

    private fun onBack() = navigationRouter.back()
}

/**
 * Maps a [ChatMessage] to a [SupportUiMessage] for the support-chat UI, returning null when the
 * message is a protocol marker (e.g. `[Category: …]`) that should not be rendered.
 *
 * Bot-prefixed messages always render on the agent side regardless of who sent them: the user's
 * own device emits `[Zapp]:` greetings/notices and the agent's device may emit announcements
 * with the same prefix — both should look like system messages, not user input.
 */
private fun ChatMessage.toSupportUiMessageOrNull(): SupportUiMessage? {
    if (content.startsWith(SupportChatConstants.CATEGORY_MARKER_PREFIX)) return null
    val isBotPrefixed = content.startsWith(SupportChatConstants.BOT_PREFIX)
    val origin =
        when {
            isBotPrefixed -> SupportMessageOrigin.BOT
            isFromMe -> SupportMessageOrigin.USER
            else -> SupportMessageOrigin.AGENT
        }
    val displayContent = if (isBotPrefixed) content.removePrefix(SupportChatConstants.BOT_PREFIX) else content
    return SupportUiMessage(
        id = id,
        content = displayContent,
        origin = origin,
        timestamp = timestamp,
    )
}
