// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.media.ImageProcessor
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.io.FileInputStream

@Composable
internal fun ImageViewerOverlay(
    message: ChatMessage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val imageModel =
        remember(message.mediaLocalPath, message.thumbnailData) {
            when {
                message.mediaLocalPath != null && File(message.mediaLocalPath).exists() -> {
                    ImageRequest
                        .Builder(context)
                        .data(File(message.mediaLocalPath))
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

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                }
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = stringResource(R.string.chat_room_image_viewer_content_description),
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        }.graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
            )
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
        ) {
            if (message.mediaLocalPath != null && File(message.mediaLocalPath).exists()) {
                IconButton(
                    onClick = { saveImageToGallery(context, message) },
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.SaveAlt,
                        contentDescription = stringResource(R.string.chat_room_image_viewer_save),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_room_image_viewer_close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun saveImageToGallery(context: Context, message: ChatMessage) {
    val filePath = message.mediaLocalPath ?: return
    val sourceFile = File(filePath)
    if (!sourceFile.exists()) return

    try {
        val mimeType = message.contentType ?: "image/jpeg"
        val extension =
            when {
                mimeType.contains("png") -> "png"
                mimeType.contains("gif") -> "gif"
                mimeType.contains("webp") -> "webp"
                else -> "jpg"
            }
        val fileName = "Zapp_${System.currentTimeMillis()}.$extension"

        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Zapp")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

        val resolver = context.contentResolver
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return

        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(sourceFile).use { it.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        Toast.makeText(context, context.getString(R.string.chat_room_image_viewer_saved), Toast.LENGTH_SHORT).show()
    } catch (e: java.io.IOException) {
        Twig.warn(e) { "ImageViewerOverlay: saveImageToGallery failed" }
        Toast.makeText(context, context.getString(R.string.chat_room_image_viewer_save_failed), Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Twig.warn(e) { "ImageViewerOverlay: saveImageToGallery denied" }
        Toast.makeText(context, context.getString(R.string.chat_room_image_viewer_save_failed), Toast.LENGTH_SHORT).show()
    }
}
