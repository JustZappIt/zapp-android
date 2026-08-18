// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.room

import android.net.Uri
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.contacts.EditChatContactState
import co.electriccoin.zcash.ui.screen.chat.list.ChatListChipVariant
import co.electriccoin.zcash.ui.screen.chat.list.ChatListConnectionStatus
import co.electriccoin.zcash.ui.screen.chat.list.ChatListDhtHealth
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConnectionDetailsUi
import co.electriccoin.zcash.ui.screen.chat.view.BlockUserDialogState
import java.math.BigDecimal

data class ChatRoomState(
    val title: StringResource,
    val subtitle: StringResource,
    val isTitleClickable: Boolean,
    val onTitleClick: () -> Unit,
    val onBack: () -> Unit,
    val networkChip: ChatRoomNetworkChipState,
    val messages: List<ChatMessage>,
    /** First message below the transient unread divider for this room entry. */
    val firstUnreadMessageId: String?,
    /** mediaId → transfer progress (0..1) for in-flight media uploads/downloads. */
    val mediaTransferProgress: Map<String, Float>,
    val localPublicKey: String?,
    val fiatRate: ZecFiatRate?,
    val isLoading: Boolean,
    val input: ChatRoomInputState,
    val onPayRequest: (ChatMessage) -> Unit,
    val onViewTransaction: (txId: String) -> Unit,
    val onSendToAddress: (String) -> Unit,
    val onCopyMessage: (ChatMessage) -> Unit,
    val attachmentSheet: ChatRoomAttachmentSheetState?,
    val mediaSheet: ChatRoomMediaSheetState?,
    val splitSheet: ChatRoomSplitSheetState?,
    val networkSheet: ChatRoomNetworkSheetState?,
    val editContactSheet: EditChatContactState?,
    val groupInfoSheet: ChatRoomGroupInfoSheetState?,
    val groupRenameDialog: ChatRoomGroupRenameDialogState?,
    val addMemberSheet: ChatRoomAddMemberSheetState?,
    val blockDialog: BlockUserDialogState?,
)

data class ChatRoomGroupInfoSheetState(
    val groupName: String,
    val members: List<ChatRoomGroupMember>,
    val onRename: () -> Unit,
    val onAddMember: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatRoomGroupMember(
    val publicKey: String,
    val displayName: String,
)

data class ChatRoomGroupRenameDialogState(
    val value: String,
    val canSave: Boolean,
    val onValueChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatRoomAddMemberSheetState(
    val contacts: List<ChatRoomAddableContact>,
    val onDismiss: () -> Unit,
)

data class ChatRoomAddableContact(
    val publicKey: String,
    val displayName: String,
    val onAdd: () -> Unit,
)

data class ChatRoomNetworkChipState(
    val text: StringResource,
    val variant: ChatListChipVariant,
    val onClick: () -> Unit,
)

data class ChatRoomInputState(
    val value: String,
    val placeholder: StringResource,
    val canSend: Boolean,
    val attachContentDescription: StringResource,
    val sendContentDescription: StringResource,
    val onChange: (String) -> Unit,
    val onSendClick: () -> Unit,
    val onAttachClick: () -> Unit,
    val onMediaCommitted: ((Uri) -> Unit)? = null,
    val replyPreview: ChatRoomReplyPreviewState? = null,
)

data class ChatRoomReplyPreviewState(
    val senderName: String,
    val content: String,
    val onDismiss: () -> Unit,
)

data class ChatRoomAttachmentSheetState(
    val isGroup: Boolean,
    val onShareAddress: () -> Unit,
    val onSendZec: () -> Unit,
    val onSplitBill: () -> Unit,
    val onAttachMedia: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatRoomSplitSheetState(
    val isGroup: Boolean,
    val participants: List<SplitParticipant>,
    val fiatRate: ZecFiatRate?,
    val onSend: (memo: String, shares: List<SplitShareInput>) -> Unit,
    val onDismiss: () -> Unit,
)

data class SplitParticipant(
    val publicKey: String,
    val displayName: String,
)

data class SplitShareInput(
    val publicKey: String,
    val displayName: String,
    val amount: BigDecimal,
)

data class ChatRoomMediaSheetState(
    val onChooseMedia: () -> Unit,
    val onAttachFile: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onShareLocation: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatRoomNetworkSheetState(
    val connectionStatus: ChatListConnectionStatus,
    val peerCount: Int,
    val dhtHealth: ChatListDhtHealth,
    val connectionDetails: ConnectionDetailsUi?,
    val onDismiss: () -> Unit,
)
