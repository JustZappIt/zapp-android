// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import java.io.File
import java.util.Locale

object FileUtils {
    fun copyUriToCache(context: Context, uri: Uri, subdirectory: String = "chat_media"): File? =
        runChatCallResult("FileUtils: copyUriToCache failed") {
            val dir = File(context.cacheDir, subdirectory).apply { mkdirs() }
            val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val destFile = File(dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile
        }.getOrNull()

    fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
        }
        return uri.lastPathSegment
    }

    fun getMimeType(context: Context, uri: Uri): String =
        context.contentResolver.getType(uri) ?: "application/octet-stream"

    fun formatFileSize(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
}
