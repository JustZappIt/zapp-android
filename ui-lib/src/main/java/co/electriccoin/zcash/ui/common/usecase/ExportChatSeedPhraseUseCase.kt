package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.common.ChatError
import co.electriccoin.zcash.ui.screen.chat.common.ChatResult
import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class ExportChatSeedPhraseUseCase(
    private val sdk: ZappMessagingSDK,
) {
    // Ask the SDK for the seed of the *current* identity rather than reading the wallet
    // seed: a messenger-only identity created before any wallet existed has its own seed,
    // so the wallet seed wouldn't recover it. exportSeedPhrase() returns the live entropy
    // of whatever identity is active, correct in every case.
    suspend operator fun invoke(): ChatResult<String> =
        runChatCallResult("ExportChatSeedPhraseUseCase: exportSeedPhrase failed") {
            sdk.exportSeedPhrase()
        }.fold(
            onSuccess = { ChatResult.Success(it) },
            onFailure = { ChatResult.Failure(ChatError.ExportSeedPhraseFailed) },
        )
}
