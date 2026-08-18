// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun ChatTermsDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val c = ZappTheme.colors
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = c.surface,
        title = {
            Text(
                stringResource(R.string.chat_terms_dialog_title),
                style = ZappTheme.typography.sectionTitle,
                color = c.text,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.chat_terms_dialog_intro),
                    style = ZappTheme.typography.body,
                    color = c.text,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.chat_terms_dialog_guidelines_heading),
                    style = ZappTheme.typography.body.copy(fontWeight = FontWeight.Bold),
                    color = c.text,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.chat_terms_dialog_body),
                    style = ZappTheme.typography.caption,
                    color = c.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(
                    stringResource(R.string.chat_terms_dialog_accept),
                    color = c.accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.chat_terms_dialog_decline), color = c.textMuted)
            }
        },
    )
}
