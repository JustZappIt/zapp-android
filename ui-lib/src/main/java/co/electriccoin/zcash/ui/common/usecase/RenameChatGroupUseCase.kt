package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class RenameChatGroupUseCase(
    private val sdk: ZappMessagingSDK,
) {
    suspend operator fun invoke(conversationId: String, name: String): Result<Unit> =
        runChatCallResult("RenameChatGroupUseCase: renameGroup failed") {
            sdk.renameGroup(conversationId, name)
        }
}
