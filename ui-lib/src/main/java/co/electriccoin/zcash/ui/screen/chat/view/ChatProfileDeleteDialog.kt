// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileDeleteDialogState
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileDeleteDialogState.Kind

@Composable
internal fun DeleteIdentityDialog(state: ChatProfileDeleteDialogState) {
    val c = ZappTheme.colors
    val isRefusal = state.kind != Kind.CONFIRM
    AlertDialog(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        shape = RectangleShape,
        title = {
            BasicText(
                text = stringResource(state.kind.title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
        },
        text = {
            BasicText(
                text = stringResource(state.kind.message),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        },
        confirmButton = {
            Row {
                state.onDeleteAnyway?.let { onDeleteAnyway ->
                    DialogTextButton(
                        label = stringResource(R.string.chat_profile_delete_conversions_delete_anyway),
                        color = c.danger,
                        onClick = onDeleteAnyway,
                    )
                }
                DialogTextButton(
                    label = stringResource(state.kind.confirm),
                    color = if (isRefusal) c.accentText else c.danger,
                    onClick = state.onConfirm,
                )
            }
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

private val Kind.title: Int
    @StringRes get() =
        when (this) {
            Kind.CONFIRM -> R.string.chat_profile_delete_dialog_title
            Kind.GIFT_CARDS_BLOCKED -> R.string.chat_profile_delete_gift_cards_title
            Kind.CONVERSIONS_BLOCKED -> R.string.chat_profile_delete_conversions_title
        }

private val Kind.message: Int
    @StringRes get() =
        when (this) {
            Kind.CONFIRM -> R.string.chat_profile_delete_dialog_message
            Kind.GIFT_CARDS_BLOCKED -> R.string.chat_profile_delete_gift_cards_message
            Kind.CONVERSIONS_BLOCKED -> R.string.chat_profile_delete_conversions_message
        }

private val Kind.confirm: Int
    @StringRes get() =
        when (this) {
            Kind.CONFIRM -> R.string.chat_profile_delete_dialog_confirm
            Kind.GIFT_CARDS_BLOCKED -> R.string.chat_profile_delete_gift_cards_review
            Kind.CONVERSIONS_BLOCKED -> R.string.chat_profile_delete_conversions_review
        }
