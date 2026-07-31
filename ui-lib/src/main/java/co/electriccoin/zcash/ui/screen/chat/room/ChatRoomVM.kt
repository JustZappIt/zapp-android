// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.room

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.AddChatGroupMemberUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetChatConnectionDetailsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetChatContactsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetChatMessagesUseCase
import co.electriccoin.zcash.ui.common.usecase.GetZashiAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatMediaDownloadCompleteUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatMediaTransferProgressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatMessageReceivedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatMessageStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatPeerStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendData
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.RenameChatGroupUseCase
import co.electriccoin.zcash.ui.common.usecase.SendChatMediaMessageUseCase
import co.electriccoin.zcash.ui.common.usecase.SendChatMessageUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.common.wallet.toZecFiatRate
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.chat.contacts.EditChatContactState
import co.electriccoin.zcash.ui.screen.chat.contacts.EditChatContactVM
import co.electriccoin.zcash.ui.screen.chat.list.ChatListChipVariant
import co.electriccoin.zcash.ui.screen.chat.list.ChatListConnectionStatus
import co.electriccoin.zcash.ui.screen.chat.list.ChatListDhtHealth
import co.electriccoin.zcash.ui.screen.chat.list.mapDhtHealth
import co.electriccoin.zcash.ui.screen.chat.media.FileUtils
import co.electriccoin.zcash.ui.screen.chat.media.ImageProcessor
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConnectionDetailsUi
import co.electriccoin.zcash.ui.screen.chat.model.ConversationType
import co.electriccoin.zcash.ui.screen.chat.model.MAX_PAYMENT_REQUEST_ZEC
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.MimeTypes
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestFiatAmount
import co.electriccoin.zcash.ui.screen.chat.model.buildPaymentRequestJson
import co.electriccoin.zcash.ui.screen.chat.model.byPublicKey
import co.electriccoin.zcash.ui.screen.chat.model.mergedWithHistory
import co.electriccoin.zcash.ui.screen.chat.model.plusMessage
import co.electriccoin.zcash.ui.screen.chat.model.resolveDisplayName
import co.electriccoin.zcash.ui.screen.chat.model.resolveSenderName
import co.electriccoin.zcash.ui.screen.chat.model.resolveSenderNames
import co.electriccoin.zcash.ui.screen.chat.model.sortedChronologically
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import co.electriccoin.zcash.ui.screen.chat.view.BlockUserDialogState
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs
import co.electriccoin.zcash.ui.screen.unifiedsend.UnifiedSendArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@Suppress("TooManyFunctions")
class ChatRoomVM(
    args: ChatRoomArgs,
    private val application: Application,
    private val chatBootstrap: ChatBootstrap,
    private val chatContactsRepository: ChatContactsRepository,
    private val chatConversationsRepository: ChatConversationsRepository,
    private val transactionRepository: TransactionRepository,
    private val getZashiAccount: GetZashiAccountUseCase,
    private val chatSendContext: ChatSendContextProvider,
    private val navigationRouter: NavigationRouter,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val observeChatMessageReceived: ObserveChatMessageReceivedUseCase,
    private val observeChatMessageStatus: ObserveChatMessageStatusUseCase,
    private val observeChatMediaDownloadComplete: ObserveChatMediaDownloadCompleteUseCase,
    private val observeChatMediaTransferProgress: ObserveChatMediaTransferProgressUseCase,
    private val observeChatPeerStatus: ObserveChatPeerStatusUseCase,
    private val getChatMessages: GetChatMessagesUseCase,
    private val getChatContacts: GetChatContactsUseCase,
    private val sendChatMessage: SendChatMessageUseCase,
    private val sendChatMediaMessage: SendChatMediaMessageUseCase,
    private val getChatConnectionDetails: GetChatConnectionDetailsUseCase,
    private val navigateToScanGenericAddress: NavigateToScanGenericAddressUseCase,
    private val validateAddress: ValidateAddressUseCase,
    private val renameChatGroup: RenameChatGroupUseCase,
    private val addChatGroupMember: AddChatGroupMemberUseCase,
    private val prefillSend: PrefillSendUseCase,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val copyToClipboard: CopyToClipboardUseCase,
) : ViewModel() {
    private val conversationId: String = args.conversationId
    private val conversation: StateFlow<ChatConversation?> =
        chatConversationsRepository
            .conversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val isLoading = MutableStateFlow(true)
    private val unreadBoundaryMessageId = MutableStateFlow<String?>(null)
    private val readGate = ChatRoomReadGate()
    private var historyJob: Job? = null

    // Status events are emitted by the worklet before its send response can resume this VM.
    // Cache the small race window so an event for the eventual server id is never lost while
    // the optimistic row still has its client id. The bounded map also absorbs stale flushes.
    private val earlyMessageStatuses = LinkedHashMap<String, MessageStatus>()

    private val showReadReceipts: StateFlow<Boolean> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_READ_RECEIPTS_ENABLED.observe(standardPreferenceProvider()))
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            // Privacy-safe cold start: reveal read state only after the persisted opt-in loads.
            false,
        )

    private val displayMessages: StateFlow<List<ChatMessage>> =
        combine(messages, showReadReceipts) { current, showReceipts ->
            current.map { it.withReadReceiptVisibility(showReceipts) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), emptyList())

    // mediaId → transfer progress (0..1) for in-flight uploads/downloads.
    private val mediaProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    // Media ids whose transfer finished; only touched from viewModelScope (main) collectors.
    private val completedMediaIds = mutableSetOf<String>()

    private val connectionStatus = MutableStateFlow(ChatListConnectionStatus.CONNECTING)
    private val peerCount = MutableStateFlow(0)
    private val dhtHealth = MutableStateFlow(ChatListDhtHealth.HEALTHY)
    private val peerOnline = MutableStateFlow<Boolean?>(null)

    // Reciprocal presence: when the user hides their own online status we also stop
    // surfacing the peer's online chip. Defaults visible until the value loads.
    private val showOnlineStatus: StateFlow<Boolean> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_SHOW_ONLINE_STATUS.observe(standardPreferenceProvider()))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), true)
    private val connectionDetails = MutableStateFlow<ConnectionDetailsUi?>(null)

    private val messageInput = MutableStateFlow("")
    private val replyingTo = MutableStateFlow<ChatMessage?>(null)
    private val showAttachmentSheet = MutableStateFlow(false)
    private val showMediaSheet = MutableStateFlow(false)
    private val splitSheetParams = MutableStateFlow<SplitSheetParams?>(null)
    private val showNetworkSheet = MutableStateFlow(false)
    private val blockDialog = MutableStateFlow<BlockUserDialogState?>(null)
    private val groupUi = MutableStateFlow(GroupUiState())

    // The unified "edit contact" sheet — same editor the contacts list uses. Owns its own form
    // state; the parent bridges its address scan and holds the backing address-book row.
    private val editSheet = MutableStateFlow<EditChatContactVM?>(null)
    private val scannedWalletAddress = MutableStateFlow<String?>(null)

    // Guards the async gap in [openEditSheet] between the null-check and assigning [editSheet].
    private var openingEditSheet = false

    private val _effects = MutableSharedFlow<ChatRoomEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<ChatRoomEffect> = _effects.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val editSheetState: StateFlow<EditChatContactState?> =
        editSheet
            .flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    init {
        loadHistory()
        observeConnection()
        observeMessageEvents()
        observePeerStatus()
    }

    fun onScreenVisible() {
        chatConversationsRepository.setActiveConversation(conversationId)
        readGate.onVisible()
        if (readGate.isHistoryLoaded) {
            resolveUnreadBoundary()
            if (readGate.consumeReadPermit()) markCurrentVisitRead()
        } else {
            loadHistory()
        }
    }

    fun onScreenHidden() {
        readGate.onHidden()
        chatConversationsRepository.releaseActiveConversation(conversationId)
    }

    override fun onCleared() {
        chatConversationsRepository.releaseActiveConversation(conversationId)
        super.onCleared()
    }

    val state: StateFlow<ChatRoomState> =
        combine(
            combine(
                conversation,
                combine(displayMessages, unreadBoundaryMessageId, ::MessageSnapshot),
                isLoading,
                mediaProgress,
                chatContactsRepository.contacts,
            ) { conv, messageSnapshot, loading, progress, contacts ->
                ContentSnapshot(
                    conversation = conv,
                    messages = messageSnapshot.messages,
                    firstUnreadMessageId = messageSnapshot.firstUnreadMessageId,
                    isLoading = loading,
                    mediaProgress = progress,
                    contacts = contacts,
                )
            },
            combine(connectionStatus, peerCount, dhtHealth, peerOnline, showOnlineStatus) { cs, pc, dh, po, show ->
                // Reciprocity hides the peer's dot while we hide our own, but it should
                // not also hide that the conversation works: suppressing our own status
                // used to strand a live conversation on "connecting". So the chip reads
                // the ungated value, and only the wording changes.
                //
                // This is not yet a transport-vs-presence split. peerOnline is still one
                // signal for both — the messaging layer emits peer_offline when a
                // connected peer hides — so a peer who hides is still shown unreachable.
                // Separating them needs a distinct connectivity event from the SDK.
                ConnectionSnapshot(
                    status = cs,
                    peerCount = pc,
                    dhtHealth = dh,
                    peerOnline = if (show) po else null,
                    isPeerReachable = po == true,
                )
            },
            combine(messageInput, replyingTo, showAttachmentSheet, showMediaSheet) { input, reply, attach, media ->
                InputSnapshot(input, reply, attach, media)
            },
            combine(
                showNetworkSheet,
                connectionDetails,
                chatConversationsRepository.localPublicKey,
                splitSheetParams,
                exchangeRateRepository.state,
            ) { net, details, localKey, splitParams, rate ->
                SheetSnapshot(
                    showNetwork = net,
                    connectionDetails = details,
                    localPublicKey = localKey,
                    splitSheet = buildSplitSheet(splitParams, rate),
                    fiatRate = zecFiatRate(rate, zecUsdPrice = null),
                )
            },
            combine(editSheetState, blockDialog, groupUi) { edit, block, group ->
                DialogSnapshot(edit, block, group)
            },
        ) { content, conn, inputSnap, sheetSnap, dlg ->
            createState(
                conversation = content.conversation,
                messages = content.messages,
                firstUnreadMessageId = content.firstUnreadMessageId,
                contacts = content.contacts,
                isLoading = content.isLoading,
                mediaTransferProgress = content.mediaProgress,
                connection = conn,
                messageInput = inputSnap.input,
                replyingTo = inputSnap.reply,
                showAttachmentSheet = inputSnap.showAttach,
                showMediaSheet = inputSnap.showMedia,
                showNetworkSheet = sheetSnap.showNetwork,
                connectionDetails = sheetSnap.connectionDetails,
                localPublicKey = sheetSnap.localPublicKey,
                splitSheet = sheetSnap.splitSheet,
                fiatRate = sheetSnap.fiatRate,
                editContact = dlg.editContact,
                blockDialog = dlg.blockDialog,
                group = dlg.group,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    conversation = null,
                    messages = emptyList(),
                    firstUnreadMessageId = null,
                    contacts = emptyList(),
                    isLoading = true,
                    mediaTransferProgress = emptyMap(),
                    connection =
                        ConnectionSnapshot(
                            status = ChatListConnectionStatus.CONNECTING,
                            peerCount = 0,
                            dhtHealth = ChatListDhtHealth.HEALTHY,
                            peerOnline = null,
                            isPeerReachable = false,
                        ),
                    messageInput = "",
                    replyingTo = null,
                    showAttachmentSheet = false,
                    showMediaSheet = false,
                    showNetworkSheet = false,
                    connectionDetails = null,
                    localPublicKey = null,
                    splitSheet = null,
                    fiatRate = null,
                    editContact = null,
                    blockDialog = null,
                    group = GroupUiState(),
                ),
        )

    private data class ContentSnapshot(
        val conversation: ChatConversation?,
        val messages: List<ChatMessage>,
        val firstUnreadMessageId: String?,
        val isLoading: Boolean,
        val mediaProgress: Map<String, Float>,
        val contacts: List<ChatContact>,
    )

    private data class MessageSnapshot(
        val messages: List<ChatMessage>,
        val firstUnreadMessageId: String?,
    )

    private data class GroupUiState(
        val showInfo: Boolean = false,
        val renameDraft: String? = null,
        val showAddMember: Boolean = false,
        val contacts: List<ChatContact> = emptyList(),
    )

    private data class DialogSnapshot(
        val editContact: EditChatContactState?,
        val blockDialog: BlockUserDialogState?,
        val group: GroupUiState,
    )

    private data class ConnectionSnapshot(
        val status: ChatListConnectionStatus,
        val peerCount: Int,
        val dhtHealth: ChatListDhtHealth,
        val peerOnline: Boolean?,
        val isPeerReachable: Boolean,
    )

    private data class InputSnapshot(
        val input: String,
        val reply: ChatMessage?,
        val showAttach: Boolean,
        val showMedia: Boolean,
    )

    private data class SheetSnapshot(
        val showNetwork: Boolean,
        val connectionDetails: ConnectionDetailsUi?,
        val localPublicKey: String?,
        val splitSheet: ChatRoomSplitSheetState?,
        val fiatRate: ZecFiatRate?,
    )

    private fun createState(
        conversation: ChatConversation?,
        messages: List<ChatMessage>,
        firstUnreadMessageId: String?,
        contacts: List<ChatContact>,
        isLoading: Boolean,
        mediaTransferProgress: Map<String, Float>,
        connection: ConnectionSnapshot,
        messageInput: String,
        replyingTo: ChatMessage?,
        showAttachmentSheet: Boolean,
        showMediaSheet: Boolean,
        showNetworkSheet: Boolean,
        connectionDetails: ConnectionDetailsUi?,
        localPublicKey: String?,
        splitSheet: ChatRoomSplitSheetState?,
        fiatRate: ZecFiatRate?,
        editContact: EditChatContactState?,
        blockDialog: BlockUserDialogState?,
        group: GroupUiState,
    ): ChatRoomState {
        val contactsByPublicKey = contacts.byPublicKey()
        val resolvedMessages = messages.resolveSenderNames(contactsByPublicKey)
        val resolvedReply = replyingTo?.resolveSenderName(contactsByPublicKey)
        return ChatRoomState(
            title =
                conversation
                    ?.resolveDisplayName(contactsByPublicKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { stringRes(it) }
                    ?: stringRes(R.string.chat_room_title_fallback),
            subtitle = subtitleText(connection),
            isTitleClickable = conversation != null,
            onTitleClick = ::onTitleClick,
            onBack = ::onBack,
            networkChip =
                ChatRoomNetworkChipState(
                    text = chipText(connection),
                    variant = chipVariant(connection),
                    onClick = ::onChipClick,
                ),
            messages = resolvedMessages,
            firstUnreadMessageId = firstUnreadMessageId,
            mediaTransferProgress = mediaTransferProgress,
            localPublicKey = localPublicKey,
            fiatRate = fiatRate,
            isLoading = isLoading,
            onPayRequest = ::onPayRequest,
            onViewTransaction = ::onViewTransaction,
            onSendToAddress = ::onSendToAddress,
            onCopyMessage = { message ->
                // Unflagged so the confirmation chip shows what was copied instead of redacting it.
                copyToClipboard(message.content, isSensitive = false)
            },
            input =
                ChatRoomInputState(
                    value = messageInput,
                    placeholder = stringRes(R.string.chat_room_input_placeholder),
                    canSend = messageInput.isNotBlank() && !isLoading,
                    attachContentDescription = stringRes(R.string.chat_room_attach_content_description),
                    sendContentDescription = stringRes(R.string.chat_room_send_content_description),
                    onChange = ::onInputChange,
                    onSendClick = ::onSendTextClick,
                    onAttachClick = ::onAttachClick,
                    onMediaCommitted = ::onMediaPicked,
                    replyPreview =
                        resolvedReply?.let { msg ->
                            ChatRoomReplyPreviewState(
                                senderName = replySenderName(msg),
                                content = msg.content.take(REPLY_PREVIEW_MAX_LENGTH),
                                onDismiss = ::dismissReply,
                            )
                        },
                ),
            attachmentSheet =
                if (showAttachmentSheet) {
                    ChatRoomAttachmentSheetState(
                        isGroup = conversation?.type == ConversationType.GROUP,
                        onShareAddress = ::onShareAddressClick,
                        onSendZec = ::onSendZecClick,
                        onSplitBill = ::onSplitBillClick,
                        onAttachMedia = ::onAttachMediaClick,
                        onDismiss = ::dismissAttachmentSheet,
                    )
                } else {
                    null
                },
            splitSheet = splitSheet,
            mediaSheet =
                if (showMediaSheet) {
                    ChatRoomMediaSheetState(
                        onChooseMedia = ::onChooseMediaClick,
                        onAttachFile = ::onAttachFileClick,
                        onTakePhoto = ::onTakePhotoClick,
                        onShareLocation = ::onShareLocationClick,
                        onDismiss = ::dismissMediaSheet,
                    )
                } else {
                    null
                },
            networkSheet =
                if (showNetworkSheet) {
                    ChatRoomNetworkSheetState(
                        connectionStatus = connection.status,
                        peerCount = connection.peerCount,
                        dhtHealth = connection.dhtHealth,
                        connectionDetails = connectionDetails,
                        onDismiss = ::dismissNetworkSheet,
                    )
                } else {
                    null
                },
            editContactSheet = editContact,
            groupInfoSheet =
                if (group.showInfo && conversation?.type == ConversationType.GROUP) {
                    ChatRoomGroupInfoSheetState(
                        groupName = conversation.displayName,
                        members = groupMembers(conversation, group.contacts),
                        onRename = ::onGroupRenameClick,
                        onAddMember = ::onAddMemberClick,
                        onDismiss = ::dismissGroupInfo,
                    )
                } else {
                    null
                },
            groupRenameDialog =
                group.renameDraft?.let { draft ->
                    ChatRoomGroupRenameDialogState(
                        value = draft,
                        canSave = draft.isNotBlank(),
                        onValueChange = ::onGroupRenameChange,
                        onSave = ::onGroupRenameConfirm,
                        onDismiss = ::dismissGroupRename,
                    )
                },
            addMemberSheet =
                if (group.showAddMember && conversation?.type == ConversationType.GROUP) {
                    ChatRoomAddMemberSheetState(
                        contacts = addableContacts(conversation, group.contacts),
                        onDismiss = ::dismissAddMember,
                    )
                } else {
                    null
                },
            blockDialog = blockDialog,
        )
    }

    private fun chipText(connection: ConnectionSnapshot): StringResource =
        when (connection.status) {
            ChatListConnectionStatus.CONNECTED -> {
                when {
                    connection.peerOnline == true -> stringRes(R.string.chat_room_chip_online)
                    connection.dhtHealth == ChatListDhtHealth.CRITICAL -> stringRes(R.string.chat_room_chip_dht)
                    connection.isPeerReachable -> stringRes(R.string.chat_room_chip_connected)
                    else -> stringRes(R.string.chat_list_status_connecting)
                }
            }

            ChatListConnectionStatus.CONNECTING -> {
                stringRes(R.string.chat_list_status_connecting)
            }

            ChatListConnectionStatus.DISCONNECTED -> {
                stringRes(R.string.chat_room_chip_off)
            }

            ChatListConnectionStatus.ERROR -> {
                stringRes(R.string.chat_room_chip_err)
            }
        }

    private fun chipVariant(connection: ConnectionSnapshot): ChatListChipVariant =
        when (connection.status) {
            ChatListConnectionStatus.CONNECTED -> {
                when {
                    connection.peerOnline == true -> ChatListChipVariant.Success
                    connection.dhtHealth == ChatListDhtHealth.CRITICAL -> ChatListChipVariant.Danger
                    connection.isPeerReachable -> ChatListChipVariant.Success
                    else -> ChatListChipVariant.Accent
                }
            }

            ChatListConnectionStatus.CONNECTING -> {
                ChatListChipVariant.Accent
            }

            ChatListConnectionStatus.DISCONNECTED,
            ChatListConnectionStatus.ERROR,
            -> {
                ChatListChipVariant.Danger
            }
        }

    private fun subtitleText(connection: ConnectionSnapshot): StringResource =
        when (connection.status) {
            ChatListConnectionStatus.CONNECTED -> {
                when {
                    connection.peerOnline == true -> {
                        stringRes(R.string.chat_room_subtitle_peer_online)
                    }

                    connection.dhtHealth == ChatListDhtHealth.CRITICAL -> {
                        stringRes(R.string.chat_room_subtitle_dht_unreachable)
                    }

                    connection.peerOnline == false -> {
                        stringRes(R.string.chat_room_subtitle_peer_offline)
                    }

                    // Reachable, but their presence is not ours to show.
                    connection.isPeerReachable -> {
                        stringRes(R.string.chat_room_subtitle_p2p_connected)
                    }

                    connection.dhtHealth == ChatListDhtHealth.DEGRADED -> {
                        stringRes(R.string.chat_room_subtitle_dht_degraded)
                    }

                    else -> {
                        stringRes(R.string.chat_room_subtitle_waiting_for_peer)
                    }
                }
            }

            ChatListConnectionStatus.CONNECTING -> {
                stringRes(R.string.chat_room_subtitle_connecting)
            }

            ChatListConnectionStatus.DISCONNECTED -> {
                stringRes(R.string.chat_room_subtitle_offline)
            }

            ChatListConnectionStatus.ERROR -> {
                stringRes(R.string.chat_room_subtitle_error)
            }
        }

    // ── Sources / observers ───────────────────────────────────────────────────

    private fun loadHistory() {
        if (readGate.isHistoryLoaded || historyJob?.isActive == true) return
        historyJob =
            viewModelScope.launch {
                isLoading.value = true
                try {
                    loadConversation()
                    if (!loadMessages()) return@launch
                    readGate.onHistoryLoaded()
                    resolveUnreadBoundary()
                    if (readGate.consumeReadPermit()) markCurrentVisitRead()
                } finally {
                    isLoading.value = false
                }
            }
    }

    private fun resolveUnreadBoundary() {
        val unreadCount =
            chatConversationsRepository.conversations.value
                ?.firstOrNull { it.id == conversationId }
                ?.unreadCount
                ?: 0
        unreadBoundaryMessageId.value = firstUnreadMessageId(messages.value, unreadCount)
    }

    private fun markCurrentVisitRead() {
        chatConversationsRepository.markConversationRead(conversationId)
        chatBootstrap.markConversationRead(conversationId)
    }

    private suspend fun loadConversation() {
        // The repository owns the conversation cache; ensure it is populated, then [conversation]
        // (derived from it) emits this room's conversation and tracks member/rename/delete edits.
        if (chatConversationsRepository.conversations.value.isNullOrEmpty()) {
            chatConversationsRepository.refresh()
        }
    }

    private suspend fun loadMessages(): Boolean =
        getChatMessages(conversationId)
            .onSuccess { zmList ->
                val history =
                    zmList
                        .map(ChatMessage::from)
                        .filterNot { msg -> chatContactsRepository.isBlocked(msg.senderId.orEmpty()) }
                // Merge, never overwrite: a message that arrived on the live stream
                // (or an optimistic send) while this fetch was in flight must survive.
                messages.update { it.mergedWithHistory(history) }
            }.isSuccess

    private fun observeConnection() {
        viewModelScope.launch {
            chatConversationsRepository.isOnline.collect { online ->
                connectionStatus.value =
                    if (online) {
                        ChatListConnectionStatus.CONNECTED
                    } else {
                        ChatListConnectionStatus.DISCONNECTED
                    }
            }
        }
        viewModelScope.launch { chatConversationsRepository.peerCount.collect { peerCount.value = it } }
        viewModelScope.launch {
            chatConversationsRepository.dhtHealth.collect { dhtHealth.value = mapDhtHealth(it) }
        }
    }

    private fun observeMessageEvents() {
        observeIncomingMessages()
        observeMessageStatus()
        observeMediaDownloads()
        observeMediaTransferProgress()
        observeGroupDeletion()
    }

    private fun observeMediaTransferProgress() =
        viewModelScope.launch {
            observeChatMediaTransferProgress().collect { (mediaId, progress) ->
                // A straggler <1.0 event after completion must not re-insert the id and
                // strand a permanent ring; completed ids are final.
                if (mediaId in completedMediaIds) return@collect
                mediaProgress.update { current ->
                    if (progress >= 1.0) {
                        completedMediaIds += mediaId
                        current - mediaId
                    } else {
                        current + (mediaId to progress.toFloat())
                    }
                }
            }
        }

    private fun observeIncomingMessages() =
        viewModelScope.launch {
            observeChatMessageReceived().collect { (incomingConvId, msg) ->
                if (incomingConvId != conversationId) return@collect
                if (chatContactsRepository.isBlocked(msg.senderId)) return@collect
                messages.update { it.plusMessage(ChatMessage.from(msg)) }
            }
        }

    private fun observeMessageStatus() =
        viewModelScope.launch {
            observeChatMessageStatus().collect { (messageId, statusConversationId, status) ->
                if (statusConversationId != conversationId) return@collect
                val mapped = mapMessageStatus(status) ?: return@collect
                var matched = false
                messages.update { list ->
                    list.map { m ->
                        if (m.id == messageId) {
                            matched = true
                            m.advanceStatus(mapped)
                        } else {
                            m
                        }
                    }
                }
                if (!matched) rememberEarlyStatus(messageId, mapped)
            }
        }

    private fun rememberEarlyStatus(messageId: String, status: MessageStatus) {
        earlyMessageStatuses[messageId] =
            earlyMessageStatuses[messageId]?.advanceTo(status) ?: status
        while (earlyMessageStatuses.size > MAX_EARLY_MESSAGE_STATUSES) {
            earlyMessageStatuses.remove(earlyMessageStatuses.keys.first())
        }
    }

    private fun mapMessageStatus(status: String): MessageStatus? =
        when (status) {
            STATUS_SENT -> MessageStatus.SENT
            STATUS_DELIVERED -> MessageStatus.DELIVERED
            STATUS_QUEUED -> MessageStatus.QUEUED
            STATUS_FAILED -> MessageStatus.FAILED
            STATUS_READ -> MessageStatus.READ
            else -> null
        }

    private fun observeMediaDownloads() =
        viewModelScope.launch {
            observeChatMediaDownloadComplete().collect { (mediaId, filePath) ->
                completedMediaIds += mediaId
                mediaProgress.update { it - mediaId }
                messages.update { list ->
                    list.map { m ->
                        if (m.mediaId == mediaId && m.mediaLocalPath == null) {
                            m.copy(mediaLocalPath = filePath)
                        } else {
                            m
                        }
                    }
                }
            }
        }

    private fun observeGroupDeletion() =
        viewModelScope.launch {
            chatConversationsRepository.conversationDeleted.collect { deletedId ->
                if (deletedId == conversationId) navigationRouter.back()
            }
        }

    private fun observePeerStatus() {
        viewModelScope.launch {
            observeChatPeerStatus().collect { (statusConvId, _, status) ->
                if (statusConvId == conversationId) {
                    peerOnline.value = status == PEER_STATUS_ONLINE
                }
            }
        }
    }

    // ── Click handlers / state mutators ───────────────────────────────────────

    private fun onBack() = navigationRouter.back()

    private fun onTitleClick() {
        when (conversation.value?.type) {
            ConversationType.DIRECT -> {
                conversation.value?.let { openEditSheet(it) }
            }

            ConversationType.GROUP -> {
                groupUi.update { it.copy(showInfo = true) }
                viewModelScope.launch { loadGroupContacts() }
            }

            null -> {
                Unit
            }
        }
    }

    private fun openEditSheet(conversation: ChatConversation) {
        if (editSheet.value != null || openingEditSheet) return
        val publicKey = conversation.participantIds.firstOrNull() ?: return
        openingEditSheet = true
        viewModelScope.launch {
            try {
                // The peer may already be a saved contact (then it carries name/addresses/blocked);
                // otherwise seed a fresh contact, prefilling any wallet address the peer shared.
                // getByPublicKey waits for the address book to actually load, so a cold-start open
                // cannot misread a saved (possibly blocked) contact as unknown.
                val existing = chatContactsRepository.getByPublicKey(publicKey)
                val contact =
                    existing ?: ChatContact(
                        publicKey = publicKey,
                        name = conversation.displayName,
                        address = resolvePeerWalletAddress(),
                    )
                editSheet.value =
                    EditChatContactVM(
                        contact = contact,
                        scope = viewModelScope,
                        scannedWalletAddressFlow = scannedWalletAddress.asStateFlow(),
                        onConsumeScannedWalletAddress = ::consumeScannedWalletAddress,
                        onScanWalletAddressRequest = ::onScanWalletAddress,
                        onSaveContact = ::onEditContactSave,
                        onDeleteContact = ::onEditContactDelete,
                        onValidateWalletAddress = ::isValidZcashAddress,
                        onDismissRequest = ::closeEditSheet,
                        onBlock = { onEditContactBlock(contact) },
                        initialWalletAddresses = contact.walletAddresses,
                        isSaved = existing != null,
                    )
            } finally {
                openingEditSheet = false
            }
        }
    }

    private suspend fun loadGroupContacts() {
        val contacts = getChatContacts()
        groupUi.update { it.copy(contacts = contacts) }
    }

    private fun groupMembers(
        conversation: ChatConversation,
        contacts: List<ChatContact>,
    ): List<ChatRoomGroupMember> {
        val localKey = chatConversationsRepository.localPublicKey.value
        val byKey = contacts.associateBy { it.publicKey }
        return conversation.participantIds
            .filter { it != localKey }
            .map { key ->
                ChatRoomGroupMember(
                    publicKey = key,
                    displayName = byKey[key]?.name?.takeIf { it.isNotBlank() } ?: shortKey(key),
                )
            }
    }

    private fun addableContacts(
        conversation: ChatConversation,
        contacts: List<ChatContact>,
    ): List<ChatRoomAddableContact> =
        contacts
            .filter { it.publicKey !in conversation.participantIds }
            .map { contact ->
                ChatRoomAddableContact(
                    publicKey = contact.publicKey,
                    displayName = contact.name.takeIf { it.isNotBlank() } ?: shortKey(contact.publicKey),
                    onAdd = { onAddMemberSelected(contact) },
                )
            }

    private fun dismissGroupInfo() {
        groupUi.update { it.copy(showInfo = false) }
    }

    private fun onGroupRenameClick() {
        val name = conversation.value?.displayName.orEmpty()
        groupUi.update { it.copy(showInfo = false, renameDraft = name) }
    }

    private fun onGroupRenameChange(value: String) {
        groupUi.update { if (it.renameDraft != null) it.copy(renameDraft = value) else it }
    }

    private fun dismissGroupRename() {
        groupUi.update { it.copy(renameDraft = null) }
    }

    private fun onGroupRenameConfirm() {
        val newName =
            groupUi.value.renameDraft
                ?.trim()
                .orEmpty()
        if (newName.isEmpty()) return
        groupUi.update { it.copy(renameDraft = null) }
        viewModelScope.launch { renameGroup(newName) }
    }

    private suspend fun renameGroup(newName: String) {
        renameChatGroup(conversationId, newName)
            .onSuccess {
                chatConversationsRepository.renameConversation(conversationId, newName)
            }.onFailure {
                _effects.tryEmit(
                    ChatRoomEffect.ShowToast(stringRes(R.string.chat_room_toast_group_rename_failed))
                )
            }
    }

    private fun onAddMemberClick() {
        groupUi.update { it.copy(showInfo = false, showAddMember = true) }
        viewModelScope.launch { loadGroupContacts() }
    }

    private fun dismissAddMember() {
        groupUi.update { it.copy(showAddMember = false) }
    }

    private fun onAddMemberSelected(contact: ChatContact) {
        groupUi.update { it.copy(showAddMember = false) }
        viewModelScope.launch { addMember(contact) }
    }

    private suspend fun addMember(contact: ChatContact) {
        addChatGroupMember(conversationId, contact.publicKey, contact.name)
            .onSuccess {
                chatConversationsRepository.refresh()
            }.onFailure {
                _effects.tryEmit(
                    ChatRoomEffect.ShowToast(stringRes(R.string.chat_room_toast_group_add_member_failed))
                )
            }
    }

    private fun closeEditSheet() {
        editSheet.value?.close()
        editSheet.value = null
    }

    private fun onScanWalletAddress() {
        viewModelScope.launch {
            val result = navigateToScanGenericAddress()
            if (result != null) scannedWalletAddress.value = result.address
        }
    }

    private fun consumeScannedWalletAddress() {
        scannedWalletAddress.value = null
    }

    private fun onChipClick() {
        viewModelScope.launch { fetchConnectionDetails() }
        showNetworkSheet.value = true
    }

    private fun onInputChange(value: String) {
        messageInput.value = value
    }

    fun onReplyToMessage(message: ChatMessage) {
        replyingTo.value = message
    }

    private fun dismissReply() {
        replyingTo.value = null
    }

    private fun replySenderName(message: ChatMessage): String =
        if (message.isFromMe) {
            application.getString(R.string.chat_room_reply_sender_self)
        } else {
            message.senderName ?: application.getString(R.string.chat_room_reply_sender_unknown)
        }

    private fun onSendTextClick() {
        val text = messageInput.value.trim()
        if (text.isEmpty()) return
        val reply = replyingTo.value
        messageInput.value = ""
        replyingTo.value = null
        viewModelScope.launch { sendTextMessage(text, reply) }
    }

    private fun onAttachClick() {
        showAttachmentSheet.value = true
    }

    private fun onShareAddressClick() {
        showAttachmentSheet.value = false
        viewModelScope.launch { shareWalletAddress() }
    }

    private fun onSendZecClick() {
        showAttachmentSheet.value = false
        viewModelScope.launch {
            // Prefer the address the peer shared in this chat; fall back to their saved
            // address-book row so Send ZEC prefills for saved contacts too.
            val peerAddress = resolvePeerWalletAddress() ?: resolveSavedContactAddress()
            chatSendContext.set(conversationId)
            navigationRouter.forward(UnifiedSendArgs(recipientAddress = peerAddress))
        }
    }

    private suspend fun resolveSavedContactAddress(): String? {
        val conv = conversation.value?.takeIf { it.type == ConversationType.DIRECT } ?: return null
        return conv.participantIds
            .firstOrNull()
            ?.let { chatContactsRepository.getByPublicKey(it)?.address }
    }

    private suspend fun isValidZcashAddress(address: String): Boolean = !validateAddress(address).isNotValid

    private fun onSplitBillClick() {
        showAttachmentSheet.value = false
        val conv =
            conversation.value ?: run {
                _effects.tryEmit(
                    ChatRoomEffect.ShowToast(stringRes(R.string.chat_room_toast_conversation_unavailable))
                )
                return
            }
        val isGroup = conv.type == ConversationType.GROUP
        val localKey = chatConversationsRepository.localPublicKey.value
        viewModelScope.launch {
            val contacts = getChatContacts().associateBy { it.publicKey }
            val participants =
                conv.participantIds
                    .filter { it != localKey }
                    .map { key ->
                        SplitParticipant(
                            publicKey = key,
                            displayName =
                                contacts[key]?.name?.takeIf { it.isNotBlank() }
                                    ?: conv.displayName.takeIf { !isGroup && it.isNotBlank() }
                                    ?: shortKey(key),
                        )
                    }
            if (participants.isEmpty()) return@launch
            splitSheetParams.value = SplitSheetParams(isGroup = isGroup, participants = participants)
        }
    }

    private fun buildSplitSheet(params: SplitSheetParams?, rate: ExchangeRateState): ChatRoomSplitSheetState? {
        if (params == null) return null
        return ChatRoomSplitSheetState(
            isGroup = params.isGroup,
            participants = params.participants,
            fiatRate = zecFiatRate(rate, zecUsdPrice = null),
            onSend = ::onCreateSplit,
            onDismiss = ::dismissSplitSheet,
        )
    }

    private fun dismissSplitSheet() {
        splitSheetParams.value = null
    }

    private fun onCreateSplit(memo: String, shares: List<SplitShareInput>) {
        splitSheetParams.value = null
        val hasInvalidAmount =
            shares.any { share ->
                share.amount <= BigDecimal.ZERO || share.amount > MAX_PAYMENT_REQUEST_ZEC.toBigDecimal()
            }
        if (shares.isEmpty() || hasInvalidAmount) {
            return
        }
        viewModelScope.launch { sendSplitRequests(memo, shares) }
    }

    private data class SplitSheetParams(
        val isGroup: Boolean,
        val participants: List<SplitParticipant>,
    )

    private fun onPayRequest(message: ChatMessage) {
        val parsed = runCatching { JSONObject(message.content) }.getOrNull() ?: return
        val requestId = parsed.optString("id", "").takeIf { it.isNotEmpty() }
        val amount =
            parsed
                .optDouble("amount", 0.0)
                .takeIf { it.isFinite() && it > 0.0 && it <= MAX_PAYMENT_REQUEST_ZEC }
        val memo = parsed.optString("memo", "").takeIf { it.isNotEmpty() }
        val requesterAddress = parsed.optString("requesterAddress", "").takeIf { it.isNotBlank() }
        viewModelScope.launch {
            val address =
                requesterAddress
                    ?: resolvePeerWalletAddress()
                    ?: resolveSavedContactAddress()
                    ?: return@launch
            if (amount != null) {
                prefillSend.request(
                    PrefillSendData.All(
                        amount = amount.convertZecToZatoshi(),
                        address = address,
                        fee = null,
                        memos = memo?.let { listOf(it) },
                    )
                )
            }
            chatSendContext.set(conversationId, requestId)
            navigationRouter.forward(UnifiedSendArgs(recipientAddress = address))
        }
    }

    private fun onViewTransaction(txId: String) {
        if (txId.isBlank()) return
        viewModelScope.launch {
            // Peer-supplied txId: navigating blindly leaves TransactionDetail loading forever
            // when the tx isn't in the local wallet (other device, not yet synced).
            val exists =
                runCatching { transactionRepository.getTransactions() }
                    .getOrDefault(emptyList())
                    .any { it.id.txIdString() == txId }
            if (exists) {
                navigationRouter.forward(TransactionDetailArgs(txId))
            } else {
                _effects.tryEmit(
                    ChatRoomEffect.ShowToast(stringRes(R.string.chat_room_toast_transaction_not_synced))
                )
            }
        }
    }

    // Mirrors onPayRequest minus amount prefill: a shared address bubble taps into a prefilled send.
    private fun onSendToAddress(address: String) {
        if (address.isBlank()) return
        chatSendContext.set(conversationId)
        navigationRouter.forward(UnifiedSendArgs(recipientAddress = address))
    }

    private fun shortKey(key: String): String =
        if (key.length <= SHORT_KEY_THRESHOLD) {
            key
        } else {
            "${key.take(SHORT_KEY_PREFIX)}…${key.takeLast(SHORT_KEY_SUFFIX)}"
        }

    private fun onAttachMediaClick() {
        showAttachmentSheet.value = false
        showMediaSheet.value = true
    }

    private fun onChooseMediaClick() {
        showMediaSheet.value = false
        _effects.tryEmit(ChatRoomEffect.PickMedia)
    }

    private fun onAttachFileClick() {
        showMediaSheet.value = false
        _effects.tryEmit(ChatRoomEffect.PickFile)
    }

    private fun onTakePhotoClick() {
        showMediaSheet.value = false
        _effects.tryEmit(ChatRoomEffect.TakePhoto)
    }

    private fun onShareLocationClick() {
        showMediaSheet.value = false
        _effects.tryEmit(ChatRoomEffect.ShareLocation)
    }

    private fun dismissAttachmentSheet() {
        showAttachmentSheet.value = false
    }

    private fun dismissMediaSheet() {
        showMediaSheet.value = false
    }

    private fun dismissNetworkSheet() {
        showNetworkSheet.value = false
    }

    private fun dismissBlockDialog() {
        blockDialog.value = null
    }

    private suspend fun onEditContactSave(
        publicKey: String,
        name: String,
        walletAddress: String,
        walletAddresses: Map<String, String>,
    ): Boolean {
        val result =
            chatContactsRepository
                .saveContact(publicKey, name, walletAddress, walletAddresses)
        return result.isSuccess
    }

    private suspend fun onEditContactDelete(publicKey: String): Boolean {
        val result = chatContactsRepository.deleteContact(publicKey)
        if (result.isSuccess) navigationRouter.back()
        return result.isSuccess
    }

    private fun onEditContactBlock(contact: ChatContact) {
        closeEditSheet()
        blockDialog.value =
            BlockUserDialogState(
                displayName = contact.name,
                isUnblock = contact.isBlocked,
                onConfirm = { onBlockConfirm(contact.publicKey, contact.name, !contact.isBlocked) },
                onDismiss = ::dismissBlockDialog,
            )
    }

    private fun onBlockConfirm(
        publicKey: String,
        displayName: String,
        isBlocked: Boolean,
    ) {
        // The dialog only dismisses on success; a failed write keeps it up so the user can retry
        // instead of walking away believing the block/unblock took effect.
        viewModelScope.launch {
            if (chatContactsRepository.setBlocked(publicKey, displayName, isBlocked).isSuccess) {
                blockDialog.value = null
                if (isBlocked) navigationRouter.back()
            }
        }
    }

    // ── External-effect entry points (View calls these after launchers fire) ─

    fun onMediaPicked(uri: Uri) {
        viewModelScope.launch { sendMediaFromUri(uri) }
    }

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch { sendFileFromUri(uri) }
    }

    fun onCameraCaptured(uri: Uri) {
        viewModelScope.launch { sendCameraCapture(uri) }
    }

    fun onLocationObtained(latitude: Double, longitude: Double, accuracy: Float) {
        viewModelScope.launch { sendLocationMessage(latitude, longitude, accuracy) }
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    private fun addOutgoingMessage(message: ChatMessage) {
        messages.update { it.plusMessage(message) }
        chatConversationsRepository.recordOutgoingMessage(message)
    }

    private suspend fun sendTextMessage(text: String, replyTo: ChatMessage? = null) {
        val optimisticId = OPTIMISTIC_MESSAGE_ID_PREFIX + UUID.randomUUID()
        val optimisticMessage =
            ChatMessage.pendingText(
                id = optimisticId,
                conversationId = conversationId,
                content = text,
                replyToId = replyTo?.id,
                replyToSenderName = replyTo?.let { replySenderName(it) },
                replyToContent = replyTo?.content?.take(REPLY_PREVIEW_MAX_LENGTH),
            )
        addOutgoingMessage(optimisticMessage)

        sendChatMessage(
            conversationId = conversationId,
            content = text,
            replyToId = optimisticMessage.replyToId,
            replyToSenderName = optimisticMessage.replyToSenderName,
            replyToContent = optimisticMessage.replyToContent,
        ).onSuccess { zmMessage ->
            val persistedMessage = ChatMessage.from(zmMessage)
            val deliveryStatus =
                earlyMessageStatuses.remove(persistedMessage.id)
                    ?: persistedMessage.status
                    ?: MessageStatus.QUEUED
            messages.update { list ->
                val reconciledMessage = persistedMessage.copy(status = deliveryStatus)
                if (list.any { it.id == optimisticId }) {
                    list
                        .map { message ->
                            if (message.id == optimisticId) reconciledMessage else message
                        }
                        // The worklet's timestamp replaces the optimistic local one
                        .sortedChronologically()
                } else {
                    list.plusMessage(reconciledMessage)
                }
            }
            chatConversationsRepository.recordOutgoingMessage(persistedMessage)
        }.onFailure {
            messages.update { list ->
                list.map { message ->
                    if (message.id == optimisticId) {
                        message.advanceStatus(MessageStatus.FAILED)
                    } else {
                        message
                    }
                }
            }
            // The failed row remains visible, and the draft/reply are restored for retry.
            if (messageInput.value.isEmpty()) messageInput.value = text
            if (replyingTo.value == null) replyingTo.value = replyTo
        }
    }

    private suspend fun sendMediaFromUri(uri: Uri) {
        runChatCall("ChatRoomVM: sendMedia failed") {
            withContext(Dispatchers.IO) {
                val mimeType = FileUtils.getMimeType(application, uri)
                val thumbnail =
                    if (mimeType.startsWith(MimeTypes.IMAGE_PREFIX)) {
                        ImageProcessor.generateThumbnail(application, uri)
                    } else {
                        null
                    }
                if (mimeType == MimeTypes.GIF) {
                    val cached =
                        FileUtils.copyUriToCache(application, uri) ?: error("Failed to cache GIF")
                    sendMediaMessage(cached.absolutePath, MimeTypes.GIF, thumbnailData = thumbnail)
                } else if (mimeType.startsWith(MimeTypes.IMAGE_PREFIX)) {
                    val compressed =
                        ImageProcessor.compressImage(application, uri)
                            ?: error("Image compression failed")
                    sendMediaMessage(compressed.absolutePath, MimeTypes.IMAGE_JPEG, thumbnailData = thumbnail)
                } else {
                    val cached =
                        FileUtils.copyUriToCache(application, uri) ?: error("Failed to cache media")
                    sendMediaMessage(cached.absolutePath, mimeType, thumbnailData = thumbnail)
                }
            }
        }
    }

    private suspend fun sendFileFromUri(uri: Uri) {
        runChatCall("ChatRoomVM: sendFile failed") {
            withContext(Dispatchers.IO) {
                val cached =
                    FileUtils.copyUriToCache(application, uri) ?: error("Failed to cache file")
                val mimeType = FileUtils.getMimeType(application, uri)
                val fileName = FileUtils.getFileName(application, uri) ?: FILE_FALLBACK_NAME
                val thumbnail =
                    if (mimeType.startsWith(MimeTypes.IMAGE_PREFIX)) {
                        ImageProcessor.generateThumbnail(application, uri)
                    } else {
                        null
                    }
                sendMediaMessage(cached.absolutePath, mimeType, fileName, thumbnail)
            }
        }
    }

    private suspend fun sendCameraCapture(uri: Uri) {
        runChatCall("ChatRoomVM: sendCameraCapture failed") {
            withContext(Dispatchers.IO) {
                val thumbnail = ImageProcessor.generateThumbnail(application, uri)
                val compressed =
                    ImageProcessor.compressImage(application, uri)
                        ?: error("Image compression failed")
                sendMediaMessage(compressed.absolutePath, MimeTypes.IMAGE_JPEG, thumbnailData = thumbnail)
            }
        }
    }

    private suspend fun sendMediaMessage(
        mediaPath: String,
        contentType: String,
        caption: String = "",
        thumbnailData: String? = null,
    ) {
        sendChatMediaMessage(conversationId, mediaPath, contentType, caption, thumbnailData).onSuccess { zmMessage ->
            addOutgoingMessage(ChatMessage.from(zmMessage))
        }
    }

    private suspend fun sendLocationMessage(latitude: Double, longitude: Double, accuracy: Float) {
        val content =
            JSONObject()
                .apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy.toDouble())
                }.toString()
        sendChatMessage(conversationId, content, MimeTypes.LOCATION).onSuccess { zmMessage ->
            addOutgoingMessage(ChatMessage.from(zmMessage))
        }
    }

    private suspend fun shareWalletAddress() {
        val address = getZashiAccount().unified.address.address
        sendChatMessage(conversationId, address, MimeTypes.WALLET_ADDRESS).onSuccess { zmMessage ->
            addOutgoingMessage(ChatMessage.from(zmMessage))
        }
    }

    private suspend fun sendSplitRequests(memo: String, shares: List<SplitShareInput>) {
        val requesterAddress = getZashiAccount().unified.address.address
        val splitCount =
            if (conversation.value?.type == ConversationType.GROUP) shares.size + 1 else 1
        val rate =
            (exchangeRateRepository.state.value as? ExchangeRateState.Data)
                ?.currencyConversion
                ?.toZecFiatRate()
        shares.forEach { share ->
            val fiat =
                rate?.let {
                    PaymentRequestFiatAmount(
                        amount =
                            it
                                .zecToFiat(share.amount)
                                .setScale(FIAT_SCALE, RoundingMode.HALF_UP),
                        currency = it.currency,
                    )
                }
            val content =
                buildPaymentRequestJson(
                    id = UUID.randomUUID().toString(),
                    amount = share.amount,
                    requesterAddress = requesterAddress,
                    memo = memo,
                    debtorId = share.publicKey,
                    debtorName = share.displayName,
                    splitCount = splitCount,
                    fiat = fiat,
                )
            sendChatMessage(conversationId, content, MimeTypes.PAYMENT_REQUEST).onSuccess { zmMessage ->
                addOutgoingMessage(ChatMessage.from(zmMessage))
            }
        }
    }

    private suspend fun fetchConnectionDetails() {
        getChatConnectionDetails()
            .onSuccess { details ->
                connectionDetails.value = ConnectionDetailsUi.from(details)
            }.onFailure {
                connectionDetails.value = null
            }
    }

    private fun resolvePeerWalletAddress(): String? =
        messages.value
            .lastOrNull { msg ->
                msg.contentType == MimeTypes.WALLET_ADDRESS && !msg.isFromMe
            }?.content
            ?.takeIf { it.isNotBlank() }

    companion object {
        private const val OPTIMISTIC_MESSAGE_ID_PREFIX = "local:"
        private const val MAX_EARLY_MESSAGE_STATUSES = 64
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_QUEUED = "queued"
        const val STATUS_FAILED = "failed"
        const val STATUS_READ = "read"
        const val PEER_STATUS_ONLINE = "online"
        const val FILE_FALLBACK_NAME = "File"
        const val REPLY_PREVIEW_MAX_LENGTH = 100
        const val SHORT_KEY_THRESHOLD = 12
        const val SHORT_KEY_PREFIX = 6
        const val SHORT_KEY_SUFFIX = 4
        const val FIAT_SCALE = 2
    }
}
