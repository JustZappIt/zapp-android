// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MimeTypes
import org.json.JSONException
import org.json.JSONObject

/** The `id` a payment-request message carries, used to match it against a paying confirmation. */
internal fun paymentRequestId(message: ChatMessage): String? =
    try {
        JSONObject(message.content).optString("id", "").takeIf { it.isNotEmpty() }
    } catch (_: JSONException) {
        null
    }

/** The set of request ids settled by `zec-transaction` confirmations in [messages]. */
internal fun paidRequestIds(messages: List<ChatMessage>): Set<String> =
    messages
        .asSequence()
        .filter { resolveContentType(it) == CONTENT_TYPE_ZEC_TRANSACTION }
        .mapNotNull { msg ->
            try {
                JSONObject(msg.content).optString("requestId", "").takeIf { it.isNotEmpty() }
            } catch (_: JSONException) {
                null
            }
        }.toSet()

internal enum class BubbleKind { PAYMENT_REQUEST, WALLET_ADDRESS, TRANSACTION, LOCATION, IMAGE, VIDEO, FILE, TEXT }

internal fun bubbleKind(message: ChatMessage): BubbleKind {
    val contentType = resolveContentType(message)
    return when {
        contentType == CONTENT_TYPE_PAYMENT_REQUEST -> BubbleKind.PAYMENT_REQUEST
        contentType == CONTENT_TYPE_WALLET_ADDRESS -> BubbleKind.WALLET_ADDRESS
        contentType == CONTENT_TYPE_ZEC_TRANSACTION -> BubbleKind.TRANSACTION
        contentType == CONTENT_TYPE_LOCATION -> BubbleKind.LOCATION
        contentType.startsWith(IMAGE_MIME_PREFIX) -> BubbleKind.IMAGE
        contentType.startsWith(VIDEO_MIME_PREFIX) -> BubbleKind.VIDEO
        message.mediaId != null -> BubbleKind.FILE
        else -> BubbleKind.TEXT
    }
}

internal fun resolveContentType(message: ChatMessage): String {
    val declared = message.contentType
    return when {
        !declared.isNullOrEmpty() && declared != CONTENT_TYPE_TEXT_PLAIN -> {
            declared
        }

        // Only a structured payload carries a content type; ordinary prose skips the parse
        message.content.firstOrNull { !it.isWhitespace() } != '{' -> {
            CONTENT_TYPE_TEXT_PLAIN
        }

        else -> {
            try {
                JSONObject(message.content).optString("contentType", "").takeIf { it.isNotEmpty() }
            } catch (_: JSONException) {
                null
            } ?: CONTENT_TYPE_TEXT_PLAIN
        }
    }
}

private const val CONTENT_TYPE_TEXT_PLAIN = "text/plain"
private const val CONTENT_TYPE_PAYMENT_REQUEST = MimeTypes.PAYMENT_REQUEST
private const val CONTENT_TYPE_WALLET_ADDRESS = MimeTypes.WALLET_ADDRESS
private const val CONTENT_TYPE_ZEC_TRANSACTION = MimeTypes.ZEC_TRANSACTION
private const val CONTENT_TYPE_LOCATION = MimeTypes.LOCATION
private const val IMAGE_MIME_PREFIX = MimeTypes.IMAGE_PREFIX
private const val VIDEO_MIME_PREFIX = MimeTypes.VIDEO_PREFIX
