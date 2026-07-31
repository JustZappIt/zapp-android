package co.electriccoin.zcash.ui.screen.chat.list

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListPreviewTest {
    @Test
    fun `recognizes legacy truncated payment request JSON`() {
        assertTrue(isPaymentRequestJsonPreview("{\"id\":\"request-id\",\"amount\":0.5"))
    }

    @Test
    fun `recognizes payment request by address marker`() {
        assertTrue(isPaymentRequestJsonPreview("{\"requesterAddress\":\"u1address\""))
    }

    @Test
    fun `does not classify transaction or text previews as requests`() {
        assertFalse(isPaymentRequestJsonPreview("{\"txId\":\"transaction-id\"}"))
        assertFalse(isPaymentRequestJsonPreview("Dinner"))
    }
}
