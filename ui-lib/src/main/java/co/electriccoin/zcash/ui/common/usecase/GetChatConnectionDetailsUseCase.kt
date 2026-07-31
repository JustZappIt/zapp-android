package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class GetChatConnectionDetailsUseCase(
    private val sdk: ZappMessagingSDK,
) {
    suspend operator fun invoke() =
        runChatCallResult("GetChatConnectionDetailsUseCase: getConnectionDetails failed") {
            sdk.getConnectionDetails()
        }
}
