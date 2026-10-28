package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiResult
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import xyz.justzappit.zappmessaging.ZappMessagingSDK

/**
 * The chat's Delete identity: the wallet's one destructive sequence, [ResetZashiUseCase], with the messaging
 * worklet stopped first. It used to be a wipe of its own, which skipped the conversions' and Private USD's teardown
 * and asked for no biometrics; both paths now refuse, authenticate and wipe the same way.
 */
class DeleteChatIdentityUseCase(
    private val sdk: ZappMessagingSDK,
    private val resetZashi: ResetZashiUseCase,
) {
    /**
     * @throws UnsharedGiftFundsException before anything is touched, unless [allowGiftDataLoss].
     * @throws UnfinishedConversionsException before anything is touched, unless [allowConversionLoss].
     */
    suspend operator fun invoke(
        allowGiftDataLoss: Boolean = false,
        allowConversionLoss: Boolean = false,
    ): ResetZashiResult =
        resetZashi(
            keepFiles = false,
            allowGiftDataLoss = allowGiftDataLoss,
            allowConversionLoss = allowConversionLoss,
            beforeWipe = {
                // Best effort, as before: a worklet that won't stop must not keep the wallet on the device. After
                // the prompt, so cancelling it leaves the chat running.
                runChatCall("DeleteChatIdentityUseCase: shutdown failed") { sdk.shutdown() }
            },
        )
}
