package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnTarget

class OnUserSavedWalletBackupUseCase(
    private val navigationRouter: NavigationRouter,
    private val walletBackupFlagStorageProvider: WalletBackupFlagStorageProvider
) {
    suspend operator fun invoke(returnTarget: WalletBackupReturnTarget = WalletBackupReturnTarget.TABS) {
        walletBackupFlagStorageProvider.store(true)
        when (returnTarget) {
            WalletBackupReturnTarget.TABS -> navigationRouter.backToRoot()
            WalletBackupReturnTarget.INVEST_SETUP -> navigationRouter.backToOrRoot(InvestIntroArgs::class)
        }
    }
}
