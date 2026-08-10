// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.media.FileUtils
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.view.MessageStatusIndicator
import co.electriccoin.zcash.ui.screen.chat.view.formatMessageTime

@Composable
internal fun FileBubble(
    message: ChatMessage,
    isFromMe: Boolean,
    transferProgress: Float? = null,
) {
    val c = ZappTheme.colors
    val fileName = message.content.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_media_option_file)
    val fileSize = message.mediaSize?.toLong()
    val accentOrText = if (isFromMe) c.onAccent else c.text
    val metaColor = if (isFromMe) c.onAccent.copy(alpha = OUTGOING_META_ALPHA) else c.textMuted

    Column(
        modifier =
            Modifier
                .widthIn(max = 280.dp)
                .background(if (isFromMe) c.accent else c.surfaceAlt, RectangleShape)
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (transferProgress != null) {
                CircularProgressIndicator(
                    progress = { transferProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(28.dp),
                    color = if (isFromMe) c.onAccent else c.accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (isFromMe) c.onAccent else c.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = fileName,
                    style = ZappTheme.typography.body.copy(color = accentOrText),
                    maxLines = 2,
                )
                if (fileSize != null && fileSize > 0) {
                    BasicText(
                        text = FileUtils.formatFileSize(fileSize),
                        style = ZappTheme.typography.caption.copy(color = metaColor),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = formatMessageTime(message.timestamp),
                style = ZappTheme.typography.caption.copy(color = metaColor),
            )
            if (isFromMe) {
                Spacer(modifier = Modifier.width(4.dp))
                MessageStatusIndicator(
                    status = message.status,
                    mutedColor = c.onAccent.copy(alpha = OUTGOING_STATUS_ALPHA),
                    readColor = c.onAccent,
                )
            }
        }
    }
}

private const val OUTGOING_META_ALPHA = 0.7f
private const val OUTGOING_STATUS_ALPHA = 0.55f
