// SPDX-License-Identifier: AGPL-3.0-only
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
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileSeedPhraseDialogState

@Composable
internal fun SeedPhraseDialog(state: ChatProfileSeedPhraseDialogState) {
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
                text = stringResource(R.string.chat_profile_seed_phrase_dialog_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(
                    text = stringResource(R.string.chat_profile_seed_phrase_dialog_message),
                    style = ZappTheme.typography.body.copy(color = c.textMuted),
                )
                SeedWordGrid(words = state.words)
            }
        },
        confirmButton = {
            DialogTextButton(
                label = stringResource(R.string.chat_profile_seed_phrase_dialog_done),
                color = c.accent,
                onClick = state.onDismiss,
            )
        },
    )
}

@Composable
private fun SeedWordGrid(words: List<String>) {
    val c = ZappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(words.take(SEED_HALF) to 0, words.drop(SEED_HALF) to SEED_HALF).forEach { (col, offset) ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                col.forEachIndexed { i, word ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BasicText(
                            text = "${offset + i + 1}".padStart(2, '0'),
                            style = ZappTheme.typography.mono.copy(color = c.textSubtle),
                        )
                        BasicText(
                            text = word,
                            style = ZappTheme.typography.rowTitle.copy(color = c.text),
                        )
                    }
                }
            }
        }
    }
}

private const val SEED_HALF = 12
