package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.unifiedsend.UnifiedSendArgs

class SendTransactionAgainUseCase(
    private val prefillSendUseCase: PrefillSendUseCase,
    private val navigationRouter: NavigationRouter
) {
    operator fun invoke(value: DetailedTransactionData) {
        prefillSendUseCase.requestFromTransactionDetail(value)
        navigationRouter.forward(UnifiedSendArgs(isScanZip321Enabled = false))
    }
}
