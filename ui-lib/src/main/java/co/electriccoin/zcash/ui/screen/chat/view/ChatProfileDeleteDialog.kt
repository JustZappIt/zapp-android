// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileDeleteDialogState

@Composable
internal fun DeleteIdentityDialog(state: ChatProfileDeleteDialogState) {
    val c = ZappTheme.colors
    AlertDialog(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        shape = RectangleShape,
        title = {
            BasicText(
                text =
                    stringResource(
                        if (state.isBlockedByGiftCards) {
                            R.string.chat_profile_delete_gift_cards_title
                        } else {
                            R.string.chat_profile_delete_dialog_title
                        }
                    ),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
        },
        text = {
            BasicText(
                text =
                    stringResource(
                        if (state.isBlockedByGiftCards) {
                            R.string.chat_profile_delete_gift_cards_message
                        } else {
                            R.string.chat_profile_delete_dialog_message
                        }
                    ),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        },
        confirmButton = {
            DialogTextButton(
                label =
                    stringResource(
                        if (state.isBlockedByGiftCards) {
                            R.string.chat_profile_delete_gift_cards_review
                        } else {
                            R.string.chat_profile_delete_dialog_confirm
                        }
                    ),
                color = if (state.isBlockedByGiftCards) c.accentText else c.danger,
                onClick = state.onConfirm,
            )
        },
        dismissButton = {
            DialogTextButton(
                label = stringResource(R.string.chat_profile_delete_dialog_cancel),
                color = c.textMuted,
                onClick = state.onDismiss,
            )
        },
    )
}
