package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import co.electriccoin.zcash.ui.screen.chat.model.ConversationType
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ConversationType as SdkConversationType

/**
 * Returns the id of the direct conversation with [publicKey], creating one if none exists yet.
 * Used by non-chat surfaces (e.g. the Request wizard) that want to send a chat message to a
 * contact without first opening the conversation. Returns null when creation fails.
 */
class GetOrCreateChatConversationUseCase(
    private val sdk: ZappMessagingSDK,
    private val chatConversationsRepository: ChatConversationsRepository,
) {
    suspend fun hasLeftDirectConversation(publicKey: String): Boolean =
        runChatCallResult("GetOrCreateChatConversationUseCase: direct status failed") {
            sdk.hasLeftDirectConversation(publicKey)
        }.getOrDefault(false)

    suspend operator fun invoke(publicKey: String, displayName: String): String? {
        chatConversationsRepository.conversations.value
            ?.firstOrNull { it.type == ConversationType.DIRECT && publicKey in it.participantIds }
            ?.let { return it.id }
        return runChatCallResult("GetOrCreateChatConversationUseCase: createConversation failed") {
            sdk
                .createConversation(
                    type = SdkConversationType.DIRECT,
                    participants = listOf(publicKey),
                    displayName = displayName,
                ).id
                .also { chatConversationsRepository.refresh() }
        }.getOrNull()
    }
}
