// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.media.ImageProcessor
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MimeTypes
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Mirrors iOS `ChatReplyPreview.maxLength` and the SDK's own preview cap. */
internal const val REPLY_WIRE_CONTENT_MAX_LENGTH = 100

/**
 * What a quoted message renders as, derived from the `replyToContentType` a reply carries.
 * [TEXT] is also the reading of a reply from a client that predates the field.
 */
internal enum class ReplyQuoteKind {
    TEXT,
    PHOTO,
    GIF,
    VIDEO,
    FILE,
    PAYMENT_REQUEST,
    TRANSACTION,
    WALLET_ADDRESS,
    LOCATION,
}

internal fun replyQuoteKind(contentType: String?): ReplyQuoteKind =
    when {
        contentType.isNullOrEmpty() || contentType == CONTENT_TYPE_TEXT_PLAIN -> ReplyQuoteKind.TEXT
        contentType == MimeTypes.PAYMENT_REQUEST -> ReplyQuoteKind.PAYMENT_REQUEST
        contentType == MimeTypes.WALLET_ADDRESS -> ReplyQuoteKind.WALLET_ADDRESS
        contentType == MimeTypes.ZEC_TRANSACTION -> ReplyQuoteKind.TRANSACTION
        contentType == MimeTypes.LOCATION -> ReplyQuoteKind.LOCATION
        contentType == MimeTypes.GIF -> ReplyQuoteKind.GIF
        contentType.startsWith(MimeTypes.IMAGE_PREFIX) -> ReplyQuoteKind.PHOTO
        contentType.startsWith(MimeTypes.VIDEO_PREFIX) -> ReplyQuoteKind.VIDEO
        else -> ReplyQuoteKind.FILE
    }

/** Only a quoted picture has something to thumbnail. */
internal val ReplyQuoteKind.showsThumbnail: Boolean
    get() = this == ReplyQuoteKind.PHOTO || this == ReplyQuoteKind.GIF || this == ReplyQuoteKind.VIDEO

/**
 * The `replyToContentType` a reply carries: the quoted message's resolved MIME type. A file
 * attachment whose type resolves to `text/plain` (a `.txt`) is labelled as a file, not as text,
 * so the quote never reads a filename as a sentence.
 */
internal fun replyWireContentType(message: ChatMessage): String {
    val resolved = resolveContentType(message)
    return if (bubbleKind(message) == BubbleKind.FILE && resolved == CONTENT_TYPE_TEXT_PLAIN) {
        CONTENT_TYPE_OCTET_STREAM
    } else {
        resolved
    }
}

/**
 * The `replyToContent` a reply carries: a one-line summary of the quoted message, never its raw
 * body. A client that predates `replyToContentType` renders this line verbatim, so it has to
 * read on its own; a newer one prefixes it with the label for its [ReplyQuoteKind]. Text keeps
 * its text, media its caption, a file its name and an address its address; a payment request or
 * transaction becomes its amount and a location its coordinates.
 */
internal fun replyWireContent(message: ChatMessage): String {
    val summary =
        when (bubbleKind(message)) {
            BubbleKind.PAYMENT_REQUEST -> zecSummary(message.content, includeMemo = true)

            BubbleKind.TRANSACTION -> zecSummary(message.content, includeMemo = false)

            BubbleKind.LOCATION -> locationSummary(message.content)

            BubbleKind.WALLET_ADDRESS,
            BubbleKind.IMAGE,
            BubbleKind.VIDEO,
            BubbleKind.FILE,
            BubbleKind.TEXT,
            -> message.content
        }
    return summary.trim().take(REPLY_WIRE_CONTENT_MAX_LENGTH)
}

private fun zecSummary(
    content: String,
    includeMemo: Boolean,
): String {
    val parsed = parseJsonObject(content)
    val amount = parsed?.optDouble("amount", 0.0)?.takeIf { it.isFinite() && it > 0 }
    if (parsed == null || amount == null) return content
    val memo = if (includeMemo) parsed.optString("memo", "") else ""
    val zec = "${formatZecAmount(amount)} ZEC"
    return if (memo.isEmpty()) zec else "$zec · $memo"
}

private fun locationSummary(content: String): String {
    val parsed = parseJsonObject(content)
    val lat = parsed?.optDouble("latitude")?.takeIf { it.isFinite() }
    val lng = parsed?.optDouble("longitude")?.takeIf { it.isFinite() }
    return if (lat == null || lng == null) content else String.format(Locale.US, "%.6f, %.6f", lat, lng)
}

private fun parseJsonObject(content: String): JSONObject? =
    try {
        JSONObject(content)
    } catch (_: JSONException) {
        null
    }

/**
 * The single line under the sender name in a quote block: the kind's label, the quoted content,
 * or both joined by a middle dot.
 */
@Composable
internal fun replyQuoteLine(
    kind: ReplyQuoteKind,
    content: String?,
): String {
    val label =
        when (kind) {
            ReplyQuoteKind.TEXT -> null
            ReplyQuoteKind.PHOTO -> stringResource(R.string.chat_room_reply_kind_photo)
            ReplyQuoteKind.GIF -> stringResource(R.string.chat_room_reply_kind_gif)
            ReplyQuoteKind.VIDEO -> stringResource(R.string.chat_room_reply_kind_video)
            ReplyQuoteKind.FILE -> stringResource(R.string.chat_room_reply_kind_file)
            ReplyQuoteKind.PAYMENT_REQUEST -> stringResource(R.string.chat_room_reply_kind_payment_request)
            ReplyQuoteKind.TRANSACTION -> stringResource(R.string.chat_room_reply_kind_transaction)
            ReplyQuoteKind.WALLET_ADDRESS -> stringResource(R.string.chat_room_reply_kind_wallet_address)
            ReplyQuoteKind.LOCATION -> stringResource(R.string.chat_room_reply_kind_location)
        }
    val body = content.orEmpty().trim()
    return when {
        label == null -> body
        body.isEmpty() -> label
        else -> "$label · $body"
    }
}

internal fun replyQuoteIcon(kind: ReplyQuoteKind): ImageVector? =
    when (kind) {
        ReplyQuoteKind.TEXT -> null
        ReplyQuoteKind.PHOTO -> Icons.Default.Image
        ReplyQuoteKind.GIF -> Icons.Default.Gif
        ReplyQuoteKind.VIDEO -> Icons.Default.Videocam
        ReplyQuoteKind.FILE -> Icons.Default.AttachFile
        ReplyQuoteKind.PAYMENT_REQUEST -> Icons.Default.Payments
        ReplyQuoteKind.TRANSACTION -> Icons.Default.Payments
        ReplyQuoteKind.WALLET_ADDRESS -> Icons.Default.AccountBalanceWallet
        ReplyQuoteKind.LOCATION -> Icons.Default.LocationOn
    }

/**
 * The small picture beside a quoted photo, GIF or video. Degrades like [MediaBubble]: the local
 * file, else the wire thumbnail, else nothing (the label still names the kind).
 */
@Composable
internal fun ReplyQuoteThumbnail(
    message: ChatMessage,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model: Any? =
        remember(message.mediaLocalPath, message.thumbnailData) {
            val local = message.mediaLocalPath?.let(::File)?.takeIf { it.exists() }
            when {
                local != null -> {
                    ImageRequest
                        .Builder(context)
                        .data(local)
                        .crossfade(true)
                        .build()
                }

                else -> {
                    ImageProcessor.decodePeerThumbnail(message.thumbnailData)
                }
            }
        }
    if (model == null) return
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .size(size)
                .clip(RectangleShape),
    )
}

private const val CONTENT_TYPE_TEXT_PLAIN = "text/plain"
private const val CONTENT_TYPE_OCTET_STREAM = "application/octet-stream"
