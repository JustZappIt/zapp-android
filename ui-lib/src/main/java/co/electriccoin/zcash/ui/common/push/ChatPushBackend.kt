package co.electriccoin.zcash.ui.common.push

data class ChatPushTopic(
    val topic: String,
    val conversationId: String,
    val writerPublicKey: String,
)

interface ChatPushBackend {
    /** Initializes distribution-specific push infrastructure when the app process starts. */
    fun initialize() = Unit

    suspend fun reconcile(
        enabled: Boolean,
        topics: List<ChatPushTopic>,
    )

    suspend fun onTokenRefresh()
}
