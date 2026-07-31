package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ConversationType as SdkConversationType

class CreateChatGroupUseCase(
    private val sdk: ZappMessagingSDK,
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
        }
}
