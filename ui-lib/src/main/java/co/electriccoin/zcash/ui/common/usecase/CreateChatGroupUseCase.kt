package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ConversationType as SdkConversationType

class CreateChatGroupUseCase(
    private val sdk: ZappMessagingSDK,
    private val chatConversationsRepository: ChatConversationsRepository,
) {
    suspend operator fun invoke(
        name: String,
        participantPublicKeys: List<String>,
    ): Result<String> =
        runChatCallResult("CreateChatGroupUseCase: createGroup failed") {
            sdk
                .createConversation(
                    type = SdkConversationType.GROUP,
                    participants = participantPublicKeys,
                    displayName = name,
                ).id
                .also { chatConversationsRepository.refresh() }
        }
}
