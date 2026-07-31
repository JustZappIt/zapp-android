package co.electriccoin.zcash.ui.common.push

import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider

/** Process-local UI state used to suppress duplicate foreground doorbells. */
class ChatNotificationState {
    @Volatile
    private var activeConversationId: String? = null

    @Volatile
    private var isInForeground: Boolean = false

    fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    fun setForeground(foreground: Boolean) {
        isInForeground = foreground
    }

    fun isActivelyViewing(conversationId: String): Boolean = isInForeground && activeConversationId == conversationId
}

internal class ChatDoorbellDecider(
    private val blockedKeys: ChatBlockedKeysStorageProvider,
    private val notificationState: ChatNotificationState,
) {
    fun shouldNotify(
        binding: ChatPushTopic?,
        deliveryEnabled: Boolean,
    ): Boolean {
        val writer =
            binding
                ?.writerPublicKey
                ?.lowercase()
                ?.removePrefix("0x")
                .orEmpty()
        val isBlocked = blockedKeys.get().any { it.lowercase().removePrefix("0x") == writer }
        val isActive = binding?.conversationId?.let(notificationState::isActivelyViewing) == true
        return listOf(deliveryEnabled, binding != null, writer.isNotBlank(), !isBlocked, !isActive).all { it }
    }
}
