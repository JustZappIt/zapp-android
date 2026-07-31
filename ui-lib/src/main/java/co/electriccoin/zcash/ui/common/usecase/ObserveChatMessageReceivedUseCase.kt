package co.electriccoin.zcash.ui.common.usecase

import xyz.justzappit.zappmessaging.ZappMessagingSDK

class ObserveChatMessageReceivedUseCase(
    private val sdk: ZappMessagingSDK,
) {
    operator fun invoke() = sdk.messageReceived
}
