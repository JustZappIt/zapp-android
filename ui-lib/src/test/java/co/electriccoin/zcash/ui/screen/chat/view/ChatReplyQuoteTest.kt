package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply summary is wire format: iOS `ChatReplyPreview` derives the same `replyToContent` and
 * `replyToContentType` from the same message, and both clients read them back through the same
 * kind table. JSON-bodied kinds (payment request, transaction, location) are covered on iOS; the
 * Android unit test JVM stubs `org.json`.
 */
class ChatReplyQuoteTest {
    private fun message(
        content: String,
        contentType: String? = "text/plain",
        mediaId: String? = null,
    ) = ChatMessage(
        id = "m",
        conversationId = "c",
        content = content,
        contentType = contentType,
        mediaId = mediaId,
    )

    @Test
    fun `a text quote keeps its text and stays a text quote`() {
        val original = message("see you at 8")

        assertEquals("see you at 8", replyWireContent(original))
        assertEquals("text/plain", replyWireContentType(original))
        assertEquals(ReplyQuoteKind.TEXT, replyQuoteKind(replyWireContentType(original)))
    }

    @Test
    fun `the wire summary is capped like the SDK preview`() {
        val long = "x".repeat(REPLY_WIRE_CONTENT_MAX_LENGTH + 40)

        assertEquals(REPLY_WIRE_CONTENT_MAX_LENGTH, replyWireContent(message(long)).length)
    }

    @Test
    fun `a photo quote carries its caption and its image type`() {
        val captioned = message("beach", contentType = MimeTypes.IMAGE_JPEG, mediaId = "ab")
        val bare = message("", contentType = MimeTypes.IMAGE_JPEG, mediaId = "ab")

        assertEquals("beach", replyWireContent(captioned))
        assertEquals("", replyWireContent(bare))
        assertEquals(MimeTypes.IMAGE_JPEG, replyWireContentType(bare))
        assertEquals(ReplyQuoteKind.PHOTO, replyQuoteKind(MimeTypes.IMAGE_JPEG))
        assertEquals(ReplyQuoteKind.GIF, replyQuoteKind(MimeTypes.GIF))
        assertEquals(ReplyQuoteKind.VIDEO, replyQuoteKind("video/mp4"))
    }

    @Test
    fun `a file quote keeps its filename and is never read as text`() {
        val pdf = message("report.pdf", contentType = "application/pdf", mediaId = "ab")
        val txt = message("notes.txt", contentType = "text/plain", mediaId = "ab")

        assertEquals("report.pdf", replyWireContent(pdf))
        assertEquals(ReplyQuoteKind.FILE, replyQuoteKind(replyWireContentType(pdf)))
        assertEquals("application/octet-stream", replyWireContentType(txt))
        assertEquals(ReplyQuoteKind.FILE, replyQuoteKind(replyWireContentType(txt)))
    }

    @Test
    fun `a wallet address quote keeps the address`() {
        val original = message("u1abcdef", contentType = MimeTypes.WALLET_ADDRESS)

        assertEquals("u1abcdef", replyWireContent(original))
        assertEquals(ReplyQuoteKind.WALLET_ADDRESS, replyQuoteKind(replyWireContentType(original)))
    }

    @Test
    fun `structured kinds map from their wire types`() {
        assertEquals(ReplyQuoteKind.PAYMENT_REQUEST, replyQuoteKind(MimeTypes.PAYMENT_REQUEST))
        assertEquals(ReplyQuoteKind.TRANSACTION, replyQuoteKind(MimeTypes.ZEC_TRANSACTION))
        assertEquals(ReplyQuoteKind.LOCATION, replyQuoteKind(MimeTypes.LOCATION))
    }

    @Test
    fun `a reply from a client without the field reads as a text quote`() {
        assertEquals(ReplyQuoteKind.TEXT, replyQuoteKind(null))
        assertEquals(ReplyQuoteKind.TEXT, replyQuoteKind(""))
    }

    @Test
    fun `only pictures show a thumbnail`() {
        assertTrue(ReplyQuoteKind.PHOTO.showsThumbnail)
        assertTrue(ReplyQuoteKind.GIF.showsThumbnail)
        assertTrue(ReplyQuoteKind.VIDEO.showsThumbnail)
        assertFalse(ReplyQuoteKind.FILE.showsThumbnail)
        assertFalse(ReplyQuoteKind.TEXT.showsThumbnail)
    }
}
