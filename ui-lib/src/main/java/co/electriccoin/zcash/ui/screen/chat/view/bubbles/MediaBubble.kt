// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import co.electriccoin.zcash.ui.screen.chat.media.MediaUiTiming
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.canRetryMedia
import co.electriccoin.zcash.ui.screen.chat.view.MessageStatusIndicator
import co.electriccoin.zcash.ui.screen.chat.view.formatMessageTime
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun MediaBubble(
    message: ChatMessage,
    isFromMe: Boolean,
    onImageClick: ((ChatMessage) -> Unit)? = null,
    onRetryMedia: ((ChatMessage) -> Unit)? = null,
    transferProgress: Float? = null,
) {
    val c = ZappTheme.colors
    Column(modifier = Modifier.widthIn(max = 260.dp).background(c.surfaceAlt, RectangleShape)) {
        MediaPreview(message, onImageClick, transferProgress)
        Column(modifier = Modifier.padding(8.dp)) {
            if (message.content.isNotBlank()) {
                BasicText(text = message.content, style = ZappTheme.typography.body.copy(color = c.text))
                Spacer(modifier = Modifier.height(2.dp))
            }
            MediaTransferLabel(message, onRetryMedia)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = formatMessageTime(message.timestamp),
                    style = ZappTheme.typography.caption.copy(color = c.textMuted),
                )
                if (isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageStatusIndicator(status = message.status, mutedColor = c.textMuted, readColor = c.accent)
                }
            }
        }
    }
}

@Composable
private fun MediaPreview(message: ChatMessage, onImageClick: ((ChatMessage) -> Unit)?, progress: Float?) {
    val isVideo = message.contentType?.startsWith("video/") == true
    val isSending = message.mediaTransferState in setOf("preparing", "sending", "downloading", "receiving")
    val aspect = mediaAspectRatio(message)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (aspect != null) {
                        Modifier.aspectRatio(aspect.coerceIn(MIN_ASPECT, MAX_ASPECT))
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
        MediaImage(message, isVideo)
        MediaTransferOverlay(isVideo, isSending, progress)
    }
}

private fun mediaAspectRatio(message: ChatMessage): Float? {
    val width = message.mediaWidth ?: 0
    val height = message.mediaHeight ?: 0
    return if (width > 0 && height > 0) width.toFloat() / height else null
}

@Composable
private fun MediaImage(message: ChatMessage, isVideo: Boolean) {
    val c = ZappTheme.colors
    val isGif = message.contentType == "image/gif"
    val model = mediaImageModel(message)
    val timing = remember(message.mediaLocalPath) { MediaUiTiming() }
    if (model != null) {
        AsyncImage(
            model = model,
            onSuccess = { timing.record("receiver_ui_available") },
            imageLoader = mediaImageLoader(isGif),
            contentDescription =
                stringResource(
                    when {
                        isGif -> R.string.chat_room_media_content_description_gif
                        isVideo -> R.string.chat_room_media_content_description_video
                        else -> R.string.chat_room_media_content_description_image
                    }
                ),
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            BasicText(
                text =
                    stringResource(
                        if (isVideo) {
                            R.string.chat_room_media_placeholder_video
                        } else {
                            R.string.chat_room_media_placeholder_image
                        }
                    ),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun mediaImageLoader(isGif: Boolean): ImageLoader {
    val context = LocalContext.current
    return remember(context, isGif) {
        ImageLoader
            .Builder(context)
            .components {
                if (isGif) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
            }.build()
    }
}

@Composable
private fun mediaImageModel(message: ChatMessage): Any? {
    val model by produceState<Any?>(null, message.mediaLocalPath, message.thumbnailData) {
        value =
            withContext(Dispatchers.IO) {
                val localPath = message.mediaLocalPath
                val isUri = localPath?.let { it.startsWith("content://") || it.startsWith("file://") } == true
                when {
                    isUri -> Uri.parse(localPath)
                    localPath != null && File(localPath).exists() -> File(localPath)
                    message.thumbnailData != null -> ImageProcessor.decodePeerThumbnail(message.thumbnailData)
                    else -> null
                }
            }
    }
    return model
}

@Composable
private fun MediaTransferOverlay(isVideo: Boolean, isSending: Boolean, progress: Float?) {
    val c = ZappTheme.colors
    if (isVideo && !isSending && progress == null) {
        Icon(
            Icons.Default.PlayCircle,
            contentDescription = stringResource(R.string.chat_room_media_play_video),
            modifier = Modifier.size(48.dp),
            tint = c.text.copy(alpha = 0.85f),
        )
    }
    if (progress != null) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(32.dp),
            color = c.accent,
            strokeWidth = 2.dp,
        )
    } else if (isSending) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = c.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun MediaTransferLabel(message: ChatMessage, onRetryMedia: ((ChatMessage) -> Unit)?) {
    val label =
        when (message.mediaTransferState) {
            "preparing" -> R.string.chat_media_preparing
            "queued" -> R.string.chat_media_queued
            "waiting_peer" -> R.string.chat_media_waiting_peer
            "sending" -> R.string.chat_media_sending
            "queued_socket" -> R.string.chat_media_awaiting_verification
            "failed" -> R.string.chat_media_failed_retry
            "downloading", "receiving" -> R.string.chat_media_downloading
            else -> null
        } ?: return
    BasicText(
        text = stringResource(label),
        style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
        modifier =
            if (message.canRetryMedia && onRetryMedia != null) {
                Modifier.clickable { onRetryMedia(message) }
            } else {
                Modifier
            },
    )
}

private const val MIN_ASPECT = 0.4f
private const val MAX_ASPECT = 2.5f
