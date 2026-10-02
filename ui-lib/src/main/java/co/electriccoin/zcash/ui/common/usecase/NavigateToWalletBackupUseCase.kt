package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackup
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnTarget

class NavigateToWalletBackupUseCase(
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke(
        isOpenedFromSeedBackupInfo: Boolean,
        returnTarget: WalletBackupReturnTarget = WalletBackupReturnTarget.TABS,
    ) {
        navigationRouter.forward(
            WalletBackup(isOpenedFromSeedBackupInfo = isOpenedFromSeedBackupInfo, returnTarget = returnTarget),
        )
    }
}
