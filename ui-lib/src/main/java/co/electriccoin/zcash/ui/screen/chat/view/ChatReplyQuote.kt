// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.media.ImageProcessor
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ReplyQuoteKind
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/** The line under a quote's sender name: the kind's icon and label, then the quoted content. */
@Composable
internal fun ReplyQuoteSummary(
    kind: ReplyQuoteKind,
    content: String?,
) {
    val c = ZappTheme.colors
    val style = kind.style()
    val label = style?.let { stringResource(it.label) }
    val body = content.orEmpty().trim()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (style != null) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        BasicText(
            text =
                when {
                    label == null -> body
                    body.isEmpty() -> label
                    else -> "$label · $body"
                },
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The picture beside a quoted photo or GIF: the local file, else the wire thumbnail, else nothing. */
@Composable
internal fun ReplyQuoteThumbnail(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model: Any? =
        remember(message.mediaLocalPath, message.thumbnailData) {
            val local = message.mediaLocalPath?.let(::File)?.takeIf { it.exists() }
            if (local != null) {
                ImageRequest
                    .Builder(context)
                    .data(local)
                    .crossfade(true)
                    .build()
            } else {
                ImageProcessor.decodePeerThumbnail(message.thumbnailData)
            }
        }
    if (model == null) return
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RectangleShape),
    )
}

private class ReplyQuoteStyle(
    val label: Int,
    val icon: ImageVector,
)

private fun ReplyQuoteKind.style(): ReplyQuoteStyle? =
    when (this) {
        ReplyQuoteKind.TEXT -> {
            null
        }

        ReplyQuoteKind.PHOTO -> {
            ReplyQuoteStyle(R.string.chat_list_photo_placeholder, Icons.Default.Image)
        }

        ReplyQuoteKind.GIF -> {
            ReplyQuoteStyle(R.string.chat_list_gif_placeholder, Icons.Default.Gif)
        }

        ReplyQuoteKind.VIDEO -> {
            ReplyQuoteStyle(R.string.chat_list_video_placeholder, Icons.Default.Videocam)
        }

        ReplyQuoteKind.FILE -> {
            ReplyQuoteStyle(R.string.chat_list_file_placeholder, Icons.Default.AttachFile)
        }

        ReplyQuoteKind.PAYMENT_REQUEST -> {
            ReplyQuoteStyle(R.string.chat_list_payment_request_placeholder, Icons.Default.Payments)
        }

        ReplyQuoteKind.TRANSACTION -> {
            ReplyQuoteStyle(R.string.chat_list_payment_placeholder, Icons.Default.Payments)
        }

        ReplyQuoteKind.WALLET_ADDRESS -> {
            ReplyQuoteStyle(R.string.chat_bubble_wallet_address, Icons.Default.AccountBalanceWallet)
        }

        ReplyQuoteKind.LOCATION -> {
            ReplyQuoteStyle(R.string.chat_list_location_placeholder, Icons.Default.LocationOn)
        }
    }
