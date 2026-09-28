package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnRoutes
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnTarget
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OnUserSavedWalletBackupUseCaseTest {
    private val router = mockk<NavigationRouter>(relaxed = true)
    private val flag = mockk<WalletBackupFlagStorageProvider>(relaxed = true)

    // As Invest binds it: its setup maps to the Invest intro, anything else to the tabs.
    private val routes =
        WalletBackupReturnRoutes { target ->
            if (target == WalletBackupReturnTarget.INVEST_SETUP) InvestIntroArgs::class else null
        }
    private val useCase = OnUserSavedWalletBackupUseCase(router, flag, routes)

    @Test
    fun `a backup started from Invest setup returns to the Invest intro`() =
        runTest {
            useCase(WalletBackupReturnTarget.INVEST_SETUP)

            coVerify { flag.store(true) }
            verify { router.backTo(InvestIntroArgs::class) }
            verify(exactly = 0) { router.backToRoot() }
        }

    @Test
    fun `any other backup returns to the tabs`() =
        runTest {
            useCase()

            coVerify { flag.store(true) }
            verify { router.backToRoot() }
        }
}
