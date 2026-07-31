// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileEditNameDialogState

@Composable
internal fun EditDisplayNameDialog(state: ChatProfileEditNameDialogState) {
    val c = ZappTheme.colors
    AlertDialog(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        shape = RectangleShape,
        title = {
            BasicText(
                text = stringResource(R.string.chat_profile_edit_name_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
        },
        text = {
            OutlinedTextField(
                value = state.value,
                onValueChange = state.onValueChange,
                singleLine = true,
                isError = state.error != null,
                supportingText =
                    state.error?.let { error ->
                        {
                            BasicText(
                                text = error.getValue(),
                                style = ZappTheme.typography.caption.copy(color = c.danger),
                            )
                        }
                    },
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            DialogTextButton(
                label =
                    stringResource(
                        if (state.isSaving) {
                            R.string.chat_display_name_updating
                        } else {
                            R.string.chat_profile_edit_name_save
                        }
                    ),
                color = if (state.canSave) c.accent else c.textSubtle,
                enabled = state.canSave,
                onClick = state.onSave,
            )
        },
        dismissButton = {
            DialogTextButton(
                label = stringResource(R.string.chat_profile_edit_name_cancel),
                color = c.textMuted,
                onClick = state.onDismiss,
            )
        },
    )
}
