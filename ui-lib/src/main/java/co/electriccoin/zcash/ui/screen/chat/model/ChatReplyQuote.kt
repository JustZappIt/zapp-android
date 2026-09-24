// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.model

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Mirrors iOS `ChatReplyPreview.maxLength` and the SDK's own preview cap. */
internal const val REPLY_WIRE_CONTENT_MAX_LENGTH = 100

/** What a quote renders as, read from its `replyToContentType`; older clients send none, which is [TEXT]. */
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
        contentType.isNullOrEmpty() || contentType == MimeTypes.TEXT_PLAIN -> ReplyQuoteKind.TEXT
        contentType == MimeTypes.PAYMENT_REQUEST -> ReplyQuoteKind.PAYMENT_REQUEST
        contentType == MimeTypes.WALLET_ADDRESS -> ReplyQuoteKind.WALLET_ADDRESS
        contentType == MimeTypes.ZEC_TRANSACTION -> ReplyQuoteKind.TRANSACTION
        contentType == MimeTypes.LOCATION -> ReplyQuoteKind.LOCATION
        contentType == MimeTypes.GIF -> ReplyQuoteKind.GIF
        contentType.startsWith(MimeTypes.IMAGE_PREFIX) -> ReplyQuoteKind.PHOTO
        contentType.startsWith(MimeTypes.VIDEO_PREFIX) -> ReplyQuoteKind.VIDEO
        else -> ReplyQuoteKind.FILE
    }

// A video has no still to show.
internal val ReplyQuoteKind.showsThumbnail: Boolean
    get() = this == ReplyQuoteKind.PHOTO || this == ReplyQuoteKind.GIF

/**
 * The `replyToContentType` a reply ships. A `.txt` attachment ships as a file, and a type the SDK
 * would reject falls back to one, so a reply is always sendable.
 */
internal fun replyWireContentType(message: ChatMessage): String {
    val kind = bubbleKind(message)
    val resolved = resolveContentType(message)
    return when {
        kind == BubbleKind.TEXT -> MimeTypes.TEXT_PLAIN
        kind == BubbleKind.FILE && resolved == MimeTypes.TEXT_PLAIN -> MimeTypes.OCTET_STREAM
        isWireMimeType(resolved) -> resolved
        else -> MimeTypes.OCTET_STREAM
    }
}

/** The `replyToContent` a reply ships: a one-line summary that also reads on its own for older clients. */
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

// The SDK's MIME rule and byte cap for `contentType` and `replyToContentType`.
private fun isWireMimeType(type: String): Boolean =
    type.toByteArray().size <= MAX_WIRE_MIME_BYTES && WIRE_MIME_TYPE.matches(type)

private fun zecSummary(
    content: String,
    includeMemo: Boolean,
): String {
    val parsed = parseJsonObject(content)
    val amount = parsed?.optDouble("amount", 0.0)?.takeIf { it.isFinite() && it > 0 }
    if (parsed == null || amount == null) return content
    val memo = if (includeMemo) parsed.optString("memo", "") else ""
    val zec = "${formatWireZec(amount)} ZEC"
    return if (memo.isEmpty()) zec else "$zec · $memo"
}

// Rounded to ZEC's 8 decimals like iOS, so both platforms ship the same line.
private fun formatWireZec(amount: Double): String =
    BigDecimal
        .valueOf(amount)
        .setScale(ZEC_DECIMALS, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

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

private val WIRE_MIME_TYPE = Regex("[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+(?:;[^\\r\\n]*)?")
private const val MAX_WIRE_MIME_BYTES = 256
private const val ZEC_DECIMALS = 8
