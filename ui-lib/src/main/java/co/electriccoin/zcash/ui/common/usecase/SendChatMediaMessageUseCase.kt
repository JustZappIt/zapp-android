package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMMessage

class SendChatMediaMessageUseCase(
    private val sdk: ZappMessagingSDK,
) {
    val transferStates get() = sdk.mediaTransferStates

    suspend fun retry(conversationId: String, messageId: String) = sdk.retryMedia(conversationId, messageId)

    suspend operator fun invoke(
        conversationId: String,
        mediaPath: String,
        contentType: String,
        caption: String,
        thumbnailData: String?,
        clientMessageId: String? = null,
    ): Result<ZMMessage> =
        runChatCallResult("SendChatMediaMessageUseCase: sendMediaMessage failed") {
            sdk.sendMediaMessage(
                conversationId,
                mediaPath,
                contentType,
                caption,
                thumbnailData,
                clientMessageId = clientMessageId,
            )
        }
}
