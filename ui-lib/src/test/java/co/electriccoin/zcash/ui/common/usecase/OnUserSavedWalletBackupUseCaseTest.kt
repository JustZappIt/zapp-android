package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OnUserSavedWalletBackupUseCaseTest {
    private val router = mockk<NavigationRouter>(relaxed = true)
    private val flag = mockk<WalletBackupFlagStorageProvider>(relaxed = true)
    private val useCase = OnUserSavedWalletBackupUseCase(router, flag)

    @Test
    fun `a backup started from Invest setup returns to the Invest intro`() =
        runTest {
            useCase(returnTo = InvestIntroArgs::class)

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
