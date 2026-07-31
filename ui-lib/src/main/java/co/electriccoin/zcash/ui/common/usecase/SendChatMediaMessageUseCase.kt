package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMMessage

class SendChatMediaMessageUseCase(
    private val sdk: ZappMessagingSDK,
) {
    suspend operator fun invoke(
        conversationId: String,
        mediaPath: String,
        contentType: String,
        caption: String,
        thumbnailData: String?,
    ): Result<ZMMessage> =
        runChatCallResult("SendChatMediaMessageUseCase: sendMediaMessage failed") {
            sdk.sendMediaMessage(conversationId, mediaPath, contentType, caption, thumbnailData)
        }
}
