// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
internal fun EmptyState(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(100.dp)
                    .background(c.surfaceAlt, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = c.textSubtle,
            )
        }

        Spacer(Modifier.height(24.dp))

        BasicText(
            text = stringResource(R.string.chat_new_conversation_empty_title),
            style =
                ZappTheme.typography.sectionTitle.copy(
                    color = c.text,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                ),
        )

        Spacer(Modifier.height(8.dp))

        BasicText(
            text = stringResource(R.string.chat_new_conversation_empty_body),
            style =
                ZappTheme.typography.body.copy(
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                ),
        )

        Spacer(Modifier.height(20.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(c.accentSoft, RectangleShape)
                    .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(c.accent, RectangleShape),
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                text = stringResource(R.string.chat_new_conversation_privacy_callout),
                style = ZappTheme.typography.body.copy(color = c.accentText),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
