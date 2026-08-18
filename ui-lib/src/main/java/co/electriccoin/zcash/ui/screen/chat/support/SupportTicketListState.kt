// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import co.electriccoin.zcash.ui.design.util.StringResource

data class SupportTicketListState(
    val tickets: List<SupportTicketItem>,
    val isLoading: Boolean,
    val onNewTicket: () -> Unit,
    val onBack: () -> Unit,
    val closeDialog: SupportLeaveDialogState?,
)

data class SupportTicketItem(
    val conversationId: String,
    val categoryLabel: StringResource,
    val lastMessage: StringResource?,
    val timeLabel: StringResource?,
    val unreadCount: Int,
    val onClick: () -> Unit,
    val onCloseSwipe: () -> Unit,
)
