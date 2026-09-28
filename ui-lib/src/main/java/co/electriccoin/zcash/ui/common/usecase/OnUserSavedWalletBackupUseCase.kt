package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnRoutes
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnTarget

class OnUserSavedWalletBackupUseCase(
    private val navigationRouter: NavigationRouter,
    private val walletBackupFlagStorageProvider: WalletBackupFlagStorageProvider,
    private val returnRoutes: WalletBackupReturnRoutes,
) {
    /** [returnTarget] names the task that sent the user to back up mid-way (Invest setup); otherwise the tabs. */
    suspend operator fun invoke(returnTarget: WalletBackupReturnTarget = WalletBackupReturnTarget.TABS) {
        walletBackupFlagStorageProvider.store(true)
        val route = returnRoutes.routeFor(returnTarget)
        if (route != null) navigationRouter.backTo(route) else navigationRouter.backToRoot()
    }
}
