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
 *
 * Submitting also leaves a return target, so closing the progress screen goes back to the
 * conversation the payment came from (where its receipt now shows) instead of the home tab.
 */
class ChatSendContextProvider {
    data class ChatSendContext(
        val conversationId: String,
        val requestId: String?,
    )

    private val ref = AtomicReference<ChatSendContext?>(null)
    private val returnTarget = AtomicReference<String?>(null)

    fun set(conversationId: String, requestId: String? = null) {
        ref.set(ChatSendContext(conversationId, requestId))
        // A new send supersedes the previous one's way back.
        returnTarget.set(null)
    }

    fun clear() {
        ref.set(null)
        returnTarget.set(null)
    }

    fun consume(): ChatSendContext? = ref.getAndSet(null)

    /**
     * Records where the send just submitted came from, for [consumeReturnTarget]. Every submit
     * calls this, with null when the send didn't start in a chat, so an earlier chat payment's
     * target can never steer an unrelated send's close.
     */
    fun markSubmitted(conversationId: String?) {
        returnTarget.set(conversationId)
    }

    /** The conversation the last submitted send came from, once; null when it wasn't from a chat. */
    fun consumeReturnTarget(): String? = returnTarget.getAndSet(null)
}
