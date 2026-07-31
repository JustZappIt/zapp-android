package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class AddChatGroupMemberUseCase(
    private val sdk: ZappMessagingSDK,
) {
    suspend operator fun invoke(
        conversationId: String,
        publicKey: String,
        displayName: String?,
    ): Result<Unit> =
        runChatCallResult("AddChatGroupMemberUseCase: addMember failed") {
            sdk.addMember(conversationId, publicKey, displayName)
        }
}
