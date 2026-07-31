package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.model.ConversationType
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository

/**
 * Resolves a wallet [address] to a direct chat conversation, when the address belongs to a chat
 * contact we already have a 1:1 conversation with. Returns the conversation id, or null when no
 * messaging-linked contact/conversation matches — the linkage is best-effort until the unified
 * contact model (phase 4) lands.
 */
class ResolveChatConversationForAddressUseCase(
    private val getChatContacts: GetChatContactsUseCase,
    private val chatConversationsRepository: ChatConversationsRepository,
) {
    suspend operator fun invoke(address: String): String? {
        if (address.isBlank()) return null
        val contact =
            getChatContacts().firstOrNull { it.address?.takeIf { addr -> addr.isNotBlank() } == address }
        return contact?.let { matched ->
            chatConversationsRepository.conversations.value
                ?.firstOrNull { conv ->
                    conv.type == ConversationType.DIRECT && matched.publicKey in conv.participantIds
                }?.id
        }
    }
}
