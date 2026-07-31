// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
internal fun PublicKeyDetectedBanner(detectedKey: String, onAdd: () -> Unit) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.accentSoft, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(onClick = onAdd)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = c.accentText,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.chat_new_conversation_public_key_detected),
                style = ZappTheme.typography.caption.copy(color = c.accentText),
            )
            BasicText(
                text = "${detectedKey.take(KEY_PREVIEW_HEAD)}...${detectedKey.takeLast(KEY_PREVIEW_TAIL)}",
                style = ZappTheme.typography.mono.copy(color = c.accentText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicText(
            text = stringResource(R.string.chat_new_conversation_add_action),
            style = ZappTheme.typography.button.copy(color = c.accentText),
        )
    }
}

private const val KEY_PREVIEW_HEAD = 12
private const val KEY_PREVIEW_TAIL = 6
