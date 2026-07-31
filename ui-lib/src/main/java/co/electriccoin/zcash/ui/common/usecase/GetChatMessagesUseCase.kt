package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMMessage

class GetChatMessagesUseCase(
    private val sdk: ZappMessagingSDK,
) {
    suspend operator fun invoke(conversationId: String): Result<List<ZMMessage>> =
        runChatCallResult("GetChatMessagesUseCase: getMessages failed") {
            sdk.getMessages(conversationId)
        }
}
