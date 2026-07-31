package co.electriccoin.zcash.ui.common.provider

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide latch that records when a ZEC send originates from a chat
 * conversation. The chat room sets the conversation id before navigating to
 * the send flow; the submit-proposal use case consumes it after a successful
 * submission to send an auto-notification message back to the peer.
 *
 * [requestId] links the send back to a specific in-chat payment request so the
 * confirmation the peer receives can flip that request to "paid".
 */
class ChatSendContextProvider {
    data class ChatSendContext(
        val conversationId: String,
        val requestId: String?,
    )

    private val ref = AtomicReference<ChatSendContext?>(null)

    fun set(conversationId: String, requestId: String? = null) {
        ref.set(ChatSendContext(conversationId, requestId))
    }

    fun clear() {
        ref.set(null)
    }

    fun consume(): ChatSendContext? = ref.getAndSet(null)
}
