// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.list

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.model.ConnectionDetailsUi

data class ChatListState(
    val title: StringResource,
    val isLoading: Boolean,
    val items: List<ChatListItemState>,
    val emptyTitle: StringResource,
    val emptySubtitle: StringResource,
    val newConversationContentDescription: StringResource,
    val onBack: () -> Unit,
    val onNewConversationClick: () -> Unit,
    val networkChip: ChatListNetworkChipState,
    val networkSheet: ChatListNetworkSheetState?,
    val tosDialog: ChatListTosDialogState?,
    val leaveDialog: ChatListLeaveDialogState?,
    /** Pinned "Zapp Support" row; always present so support is reachable from Chats. */
    val supportRow: ChatListSupportRowState,
)

data class ChatListItemState(
    val id: String,
    val displayName: String,
    val isGroup: Boolean,
    /** Direct rows only: true while the peer has an active P2P connection to us. */
    val isPeerOnline: Boolean = false,
    val lastMessage: StringResource,
    val timeLabel: StringResource?,
    val unreadCount: Int,
    val onClick: () -> Unit,
    val onLeaveSwipe: () -> Unit,
)

data class ChatListNetworkChipState(
    val text: StringResource,
    val variant: ChatListChipVariant,
    val onClick: () -> Unit,
)

enum class ChatListChipVariant { Success, Accent, Danger }

data class ChatListNetworkSheetState(
    val connectionStatus: ChatListConnectionStatus,
    val peerCount: Int,
    val dhtHealth: ChatListDhtHealth,
    val connectionDetails: ConnectionDetailsUi?,
    val onDismiss: () -> Unit,
)

data class ChatListTosDialogState(
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
)

data class ChatListLeaveDialogState(
    val conversationName: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Aggregate state for the pinned "Zapp Support" parent row. Tapping opens the ticket list,
 * not an individual chat. The row is permanent; [subtitle] carries the latest ticket
 * message, the open-ticket count, or an invitation to get help when there are no tickets.
 */
data class ChatListSupportRowState(
    val subtitle: StringResource,
    val totalUnreadCount: Int,
    val onClick: () -> Unit,
)
