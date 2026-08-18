// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.media.ImageProcessor
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.view.MessageStatusIndicator
import co.electriccoin.zcash.ui.screen.chat.view.formatMessageTime
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

@Composable
internal fun MediaBubble(
    message: ChatMessage,
    isFromMe: Boolean,
    onImageClick: ((ChatMessage) -> Unit)? = null,
    transferProgress: Float? = null,
) {
    val c = ZappTheme.colors
    val isVideo = message.contentType?.startsWith("video/") == true
    val isGif = message.contentType == "image/gif"
    val isSending = message.mediaTransferState == "sending"

    val context = LocalContext.current
    val gifImageLoader =
        remember(context) {
            ImageLoader
                .Builder(context)
                .components {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }.build()
        }
    val defaultImageLoader = remember(context) { ImageLoader(context) }

    val hasLocalFile =
        message.mediaLocalPath != null &&
            File(message.mediaLocalPath).exists()

    val imageModel: Any? =
        remember(message.mediaLocalPath, message.thumbnailData) {
            when {
                hasLocalFile -> {
                    ImageRequest
                        .Builder(context)
                        .data(File(message.mediaLocalPath))
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(true)
                        .build()
                }

                message.thumbnailData != null -> {
                    ImageProcessor.decodePeerThumbnail(message.thumbnailData)
                }

                else -> {
                    null
                }
            }
        }

    val aspectRatio =
        remember(message.mediaWidth, message.mediaHeight) {
            val w = message.mediaWidth
            val h = message.mediaHeight
            if (w != null && h != null && w > 0 && h > 0) {
                w.toFloat() / h.toFloat()
            } else {
                null
            }
        }

    Column(
        modifier =
            Modifier
                .widthIn(max = 260.dp)
                .background(c.surfaceAlt, RectangleShape),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (aspectRatio != null) {
                            Modifier.aspectRatio(aspectRatio.coerceIn(MIN_ASPECT, MAX_ASPECT))
                        } else {
                            Modifier.heightIn(min = 120.dp, max = 300.dp)
                        }
                    ).clip(RectangleShape)
                    .then(
                        if (onImageClick != null && !isVideo && !isSending) {
                            Modifier.clickable { onImageClick(message) }
                        } else {
                            Modifier
                        }
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    imageLoader = if (isGif) gifImageLoader else defaultImageLoader,
                    contentDescription =
                        when {
                            isGif -> stringResource(R.string.chat_room_media_content_description_gif)
                            isVideo -> stringResource(R.string.chat_room_media_content_description_video)
                            else -> stringResource(R.string.chat_room_media_content_description_image)
                        },
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text =
                            if (isVideo) {
                                stringResource(R.string.chat_room_media_placeholder_video)
                            } else {
                                stringResource(R.string.chat_room_media_placeholder_image)
                            },
                        style =
                            ZappTheme.typography.caption.copy(
                                color = c.textMuted,
                            ),
                    )
                }
            }

            // Incoming downloads surface via transferProgress (isSending is outbound-only); the ring
            // owns the centered overlay while a transfer is in flight, so hide the play icon then.
            if (isVideo && !isSending && transferProgress == null) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = stringResource(R.string.chat_room_media_play_video),
                    modifier = Modifier.size(48.dp),
                    tint = c.text.copy(alpha = 0.85f),
                )
            }

            if (transferProgress != null) {
                CircularProgressIndicator(
                    progress = { transferProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(32.dp),
                    color = c.accent,
                    strokeWidth = 2.dp,
                )
            } else if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = c.accent,
                    strokeWidth = 2.dp,
                )
            }
        }

        Column(modifier = Modifier.padding(8.dp)) {
            if (message.content.isNotBlank()) {
                BasicText(
                    text = message.content,
                    style = ZappTheme.typography.body.copy(color = c.text),
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = formatMessageTime(message.timestamp),
                    style =
                        ZappTheme.typography.caption.copy(
                            color = c.textMuted,
                        ),
                )
                if (isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageStatusIndicator(
                        status = message.status,
                        mutedColor = c.textMuted,
                        readColor = c.accent,
                    )
                }
            }
        }
    }
}

private const val MIN_ASPECT = 0.4f
private const val MAX_ASPECT = 2.5f
