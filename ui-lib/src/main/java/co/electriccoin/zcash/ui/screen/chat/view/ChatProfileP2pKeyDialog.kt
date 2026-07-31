// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileP2pKeyDialogState

@Composable
internal fun P2pWalletKeyDialog(state: ChatProfileP2pKeyDialogState) {
    val c = ZappTheme.colors
    if (shouldSecureScreen) {
        SecureScreen()
    }
    AlertDialog(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        shape = RectangleShape,
        title = {
            BasicText(
                text = stringResource(R.string.chat_profile_p2p_key_dialog_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(
                    text = stringResource(R.string.chat_profile_p2p_key_dialog_message),
                    style = ZappTheme.typography.body.copy(color = c.textMuted),
                )
                P2pKeyField(
                    label = stringResource(R.string.chat_profile_p2p_key_address_label),
                    value = state.address,
                    onCopy = state.onCopyAddress,
                )
                P2pKeyField(
                    label = stringResource(R.string.chat_profile_p2p_key_private_key_label),
                    value = state.privateKeyHex,
                    onCopy = state.onCopyPrivateKey,
                )
            }
        },
        confirmButton = {
            DialogTextButton(
                label = stringResource(R.string.chat_profile_p2p_key_dialog_done),
                color = c.accent,
                onClick = state.onDismiss,
            )
        },
    )
}

@Composable
private fun P2pKeyField(label: String, value: String, onCopy: () -> Unit) {
    val c = ZappTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = label,
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
                modifier = Modifier.weight(1f),
            )
            DialogTextButton(
                label = stringResource(R.string.chat_profile_p2p_key_copy),
                color = c.accent,
                onClick = onCopy,
            )
        }
        BasicText(
            text = value,
            style = ZappTheme.typography.mono.copy(color = c.textMuted),
        )
    }
}
