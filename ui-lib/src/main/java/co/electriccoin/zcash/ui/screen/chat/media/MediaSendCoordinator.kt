// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.media

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Retains the same client ID across preparation failures and ambiguous IPC responses. */
internal class MediaSendCoordinator(
    private val context: Context,
    private val conversationId: () -> String?,
    private val publish: (ChatMessage) -> Unit,
    private val send: suspend (String, String, String, String, String?, String) -> ChatMessage,
    private val retryAccepted: suspend (String, String) -> Unit,
) {
    private data class Source(
        val uri: Uri,
        val asFile: Boolean,
        val message: ChatMessage
    )

    private data class Prepared(
        val file: File,
        val mime: String,
        val thumbnail: String?,
        val caption: String
    )

    private val sources = mutableMapOf<String, Source>()
    private val running = mutableSetOf<String>()
    private val preparationSlots = Semaphore(2)

    suspend fun send(uri: Uri, asFile: Boolean = false) {
        val convId = conversationId() ?: return
        val id = UUID.randomUUID().toString()
        val message =
            ChatMessage(
                id = id,
                conversationId = convId,
                content = "",
                contentType = "image/jpeg",
                isFromMe = true,
                mediaLocalPath = uri.toString(),
                mediaTransferState = "preparing",
                status = MessageStatus.SENDING,
            )
        if (sources.size >= MAX_PENDING) {
            publish(message.copy(mediaTransferState = "failed", status = MessageStatus.FAILED))
            return
        }
        sources[id] = Source(uri, asFile, message)
        prepareAndSend(sources.getValue(id))
    }

    suspend fun retry(message: ChatMessage) {
        val source =
            sources[message.id] ?: if (message.mediaId == null && message.mediaLocalPath != null) {
                Source(Uri.parse(message.mediaLocalPath), false, message)
            } else {
                null
            }
        if (source != null) {
            prepareAndSend(source)
        } else {
            publish(message.copy(mediaTransferState = "queued"))
            runChatCallResult("MediaSendCoordinator: retry failed") {
                retryAccepted(message.conversationId, message.id)
            }.onFailure { publish(message.copy(mediaTransferState = "failed")) }
        }
    }

    private suspend fun prepareAndSend(source: Source) {
        val message = source.message
        if (!running.add(message.id)) return
        val timing = MediaUiTiming()
        publish(message.copy(mediaTransferState = "preparing", status = MessageStatus.SENDING))
        var temporaryFile: File? = null
        try {
            runChatCallResult("MediaSendCoordinator: send failed") {
                val prepared =
                    preparationSlots.withPermit {
                        withContext(Dispatchers.IO) {
                            val mime = FileUtils.getMimeType(context, source.uri)
                            val thumbnail =
                                if (mime.startsWith("image/")) {
                                    ImageProcessor.generateThumbnail(context, source.uri)
                                } else {
                                    null
                                }
                            val compress = !source.asFile && mime.startsWith("image/") && mime != "image/gif"
                            val file =
                                if (compress) {
                                    ImageProcessor.compressImage(
                                        context,
                                        source.uri
                                    )
                                } else {
                                    FileUtils.copyUriToCache(context, source.uri)
                                }
                            checkNotNull(file) { "Media preparation failed" }
                            temporaryFile = file
                            timing.record("selection_prepared", file.length())
                            Prepared(
                                file,
                                if (compress) "image/jpeg" else mime,
                                thumbnail,
                                if (source.asFile) {
                                    FileUtils.getFileName(context, source.uri).orEmpty()
                                } else {
                                    message.content
                                },
                            )
                        }
                    }
                val file = prepared.file
                val mime = prepared.mime
                val thumbnail = prepared.thumbnail
                val caption = prepared.caption
                publish(
                    message.copy(
                        content = caption,
                        contentType = mime,
                        thumbnailData = thumbnail,
                        mediaLocalPath = file.absolutePath,
                        mediaTransferState = "queued"
                    )
                )
                val accepted = send(message.conversationId, file.absolutePath, mime, caption, thumbnail, message.id)
                timing.record("local_accepted")
                publish(accepted)
                sources.remove(message.id)
            }.onFailure {
                publish(message.copy(mediaTransferState = "failed", status = MessageStatus.FAILED))
            }
        } finally {
            running.remove(message.id)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runChatCall("MediaSendCoordinator: cache cleanup failed") { temporaryFile?.delete() }
            }
        }
    }

    private companion object {
        const val MAX_PENDING = 8
    }
}

/** Enable with `adb shell setprop log.tag.ZappMediaTiming DEBUG`; contains no media/peer/path IDs. */
internal class MediaUiTiming {
    private val enabled = Log.isLoggable("ZappMediaTiming", Log.DEBUG)
    private val id = if (enabled) UUID.randomUUID().toString() else ""
    private val started = SystemClock.elapsedRealtime()

    fun record(stage: String, bytes: Long = 0) {
        if (enabled && stage in setOf("selection_prepared", "local_accepted", "receiver_ui_available")) {
            Log.d("ZappMediaTiming", "$id $stage elapsedMs=${SystemClock.elapsedRealtime() - started} bytes=$bytes")
        }
    }
}
