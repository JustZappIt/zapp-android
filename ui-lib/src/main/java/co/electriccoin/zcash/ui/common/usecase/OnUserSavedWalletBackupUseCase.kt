package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import kotlin.reflect.KClass

class OnUserSavedWalletBackupUseCase(
    private val navigationRouter: NavigationRouter,
    private val walletBackupFlagStorageProvider: WalletBackupFlagStorageProvider,
) {
    /** [returnTo] is the screen that sent the user to back up mid-task (Invest setup); otherwise the tabs. */
    suspend operator fun invoke(returnTo: KClass<*>? = null) {
        walletBackupFlagStorageProvider.store(true)
        if (returnTo != null) navigationRouter.backTo(returnTo) else navigationRouter.backToRoot()
    }
}
