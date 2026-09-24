// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The summary is wire format: iOS `ChatReplyPreview` must derive the same lines from the same messages.
class ChatReplyQuoteTest {
    @Test
    fun `a text quote keeps its text and stays a text quote`() {
        val original = message("see you at 8")

        assertEquals("see you at 8", replyWireContent(original))
        assertEquals(MimeTypes.TEXT_PLAIN, replyWireContentType(original))
        assertEquals(ReplyQuoteKind.TEXT, replyQuoteKind(replyWireContentType(original)))
    }

    @Test
    fun `the summary is capped like the SDK preview`() {
        val long = "x".repeat(REPLY_WIRE_CONTENT_MAX_LENGTH + 40)

        assertEquals(REPLY_WIRE_CONTENT_MAX_LENGTH, replyWireContent(message(long)).length)
    }

    @Test
    fun `media keeps its caption and a file its name, and a txt file is never read as text`() {
        val photo = message("beach", contentType = MimeTypes.IMAGE_JPEG, mediaId = "ab")
        val txt = message("notes.txt", contentType = MimeTypes.TEXT_PLAIN, mediaId = "ab")

        assertEquals("beach", replyWireContent(photo))
        assertEquals(MimeTypes.IMAGE_JPEG, replyWireContentType(photo))
        assertEquals("notes.txt", replyWireContent(txt))
        assertEquals(MimeTypes.OCTET_STREAM, replyWireContentType(txt))
    }

    @Test
    fun `a payment request becomes its amount and memo, rounded to ZEC's 8 decimals`() {
        val request = message("""{"amount":0.5,"memo":"Dinner"}""", contentType = MimeTypes.PAYMENT_REQUEST)
        val third =
            message("""{"amount":0.3333333333333333,"memo":"Dinner"}""", contentType = MimeTypes.PAYMENT_REQUEST)
        val noMemo = message("""{"amount":10.0}""", contentType = MimeTypes.PAYMENT_REQUEST)

        assertEquals("0.5 ZEC · Dinner", replyWireContent(request))
        assertEquals("0.33333333 ZEC · Dinner", replyWireContent(third))
        assertEquals("10 ZEC", replyWireContent(noMemo))
    }

    @Test
    fun `a transaction becomes its amount and a location its coordinates`() {
        val tx = message("""{"amount":0.5,"memo":"rent"}""", contentType = MimeTypes.ZEC_TRANSACTION)
        val location = message("""{"latitude":48.8584,"longitude":2.2945}""", contentType = MimeTypes.LOCATION)

        assertEquals("0.5 ZEC", replyWireContent(tx))
        assertEquals("48.858400, 2.294500", replyWireContent(location))
    }

    @Test
    fun `a payload that doesn't parse ships as it is`() {
        assertEquals("{broken", replyWireContent(message("{broken", contentType = MimeTypes.PAYMENT_REQUEST)))
    }

    @Test
    fun `a reply always ships a type the SDK accepts`() {
        val jsonText = message("""{"contentType":"hi"}""")
        val badDeclared = message("x", contentType = "image/ bad", mediaId = "ab")

        assertEquals(MimeTypes.TEXT_PLAIN, replyWireContentType(jsonText))
        assertEquals(MimeTypes.OCTET_STREAM, replyWireContentType(badDeclared))
    }

    @Test
    fun `wire types map to kinds, and none reads as text`() {
        assertEquals(ReplyQuoteKind.TEXT, replyQuoteKind(null))
        assertEquals(ReplyQuoteKind.PHOTO, replyQuoteKind(MimeTypes.IMAGE_JPEG))
        assertEquals(ReplyQuoteKind.GIF, replyQuoteKind(MimeTypes.GIF))
        assertEquals(ReplyQuoteKind.VIDEO, replyQuoteKind("video/mp4"))
        assertEquals(ReplyQuoteKind.FILE, replyQuoteKind(MimeTypes.OCTET_STREAM))
        assertEquals(ReplyQuoteKind.PAYMENT_REQUEST, replyQuoteKind(MimeTypes.PAYMENT_REQUEST))
        assertEquals(ReplyQuoteKind.TRANSACTION, replyQuoteKind(MimeTypes.ZEC_TRANSACTION))
        assertEquals(ReplyQuoteKind.WALLET_ADDRESS, replyQuoteKind(MimeTypes.WALLET_ADDRESS))
        assertEquals(ReplyQuoteKind.LOCATION, replyQuoteKind(MimeTypes.LOCATION))
    }

    @Test
    fun `only photos and GIFs show a thumbnail`() {
        assertTrue(ReplyQuoteKind.PHOTO.showsThumbnail)
        assertTrue(ReplyQuoteKind.GIF.showsThumbnail)
        assertFalse(ReplyQuoteKind.VIDEO.showsThumbnail)
    }

    private fun message(
        content: String,
        contentType: String? = MimeTypes.TEXT_PLAIN,
        mediaId: String? = null,
    ) = ChatMessage(id = "m", conversationId = "c", content = content, contentType = contentType, mediaId = mediaId)
}
