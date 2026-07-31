// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.list.ChatListLeaveDialogState

@Composable
internal fun LeaveConfirmationDialog(state: ChatListLeaveDialogState) {
    ConfirmDialog(
        title = stringRes(R.string.chat_list_leave_dialog_title),
        body = stringRes(R.string.chat_list_leave_dialog_message, state.conversationName),
        confirmLabel = stringRes(R.string.chat_list_leave_dialog_confirm),
        cancelLabel = stringRes(R.string.chat_list_leave_dialog_cancel),
        onConfirm = state.onConfirm,
        onDismiss = state.onDismiss,
    )
}
