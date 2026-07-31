package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class UpdateChatDisplayNameUseCase(
    private val sdk: ZappMessagingSDK,
) {
    suspend operator fun invoke(displayName: String): Result<Unit> =
        runChatCallResult("UpdateChatDisplayNameUseCase: updateDisplayName failed") {
            sdk.updateDisplayName(displayName)
        }
}
