// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
internal fun BlockUserDialog(state: BlockUserDialogState) {
    val c = ZappTheme.colors
    val target = state.displayName.ifBlank { stringResource(R.string.chat_moderation_fallback_user_name) }
    AlertDialog(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                stringResource(
                    if (state.isUnblock) R.string.chat_unblock_dialog_title else R.string.chat_block_dialog_title
                ),
                style = ZappTheme.typography.sectionTitle,
                color = c.text,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (state.isUnblock) {
                            R.string.chat_unblock_dialog_message_fmt
                        } else {
                            R.string.chat_block_dialog_message_fmt
                        },
                        target,
                    ),
                    style = ZappTheme.typography.body,
                    color = c.text,
                )
                if (!state.isUnblock) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chat_block_dialog_explanation),
                        style = ZappTheme.typography.caption,
                        color = c.textMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = state.onConfirm) {
                Text(
                    stringResource(
                        if (state.isUnblock) {
                            R.string.chat_unblock_dialog_confirm
                        } else {
                            R.string.chat_block_dialog_confirm
                        }
                    ),
                    color = ZappTheme.colors.danger,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = state.onDismiss) {
                Text(
                    stringResource(
                        if (state.isUnblock) {
                            R.string.chat_unblock_dialog_cancel
                        } else {
                            R.string.chat_block_dialog_cancel
                        }
                    ),
                    color = c.textMuted,
                )
            }
        },
    )
}
