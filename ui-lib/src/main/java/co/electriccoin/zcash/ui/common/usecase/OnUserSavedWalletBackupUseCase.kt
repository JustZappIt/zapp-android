package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupReturnRoute

class OnUserSavedWalletBackupUseCase(
    private val navigationRouter: NavigationRouter,
    private val walletBackupFlagStorageProvider: WalletBackupFlagStorageProvider,
    private val walletBackupReturnRoute: WalletBackupReturnRoute,
) {
    suspend operator fun invoke() {
        walletBackupFlagStorageProvider.store(true)
        val returnTo = walletBackupReturnRoute.route
        walletBackupReturnRoute.route = null
        if (returnTo != null) navigationRouter.backTo(returnTo) else navigationRouter.backToRoot()
    }
}
