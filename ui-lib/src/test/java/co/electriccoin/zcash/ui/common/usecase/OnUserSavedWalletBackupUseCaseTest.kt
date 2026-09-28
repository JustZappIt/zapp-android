package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.serialization.generateHashCode
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnRoutes
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnTarget
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

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
            verify { router.backToOrRoot(InvestIntroArgs::class) }
            verify(exactly = 0) { router.backToRoot() }
        }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `the return goes back to the intro when it is on the stack, and to the tabs when it is gone`() {
        val intro = InvestIntroArgs::class.serializer().generateHashCode()
        val tabs = 1

        assertEquals(
            NavigationCommand.BackTo(InvestIntroArgs::class),
            NavigationCommand.BackToOrRoot.resolve(InvestIntroArgs::class, listOf(tabs, intro, 2, 3)),
        )
        // Also when the intro is already on top, which is where popBackStack's "false" would mislead.
        assertEquals(
            NavigationCommand.BackTo(InvestIntroArgs::class),
            NavigationCommand.BackToOrRoot.resolve(InvestIntroArgs::class, listOf(tabs, intro)),
        )
        assertEquals(
            NavigationCommand.BackToRoot,
            NavigationCommand.BackToOrRoot.resolve(InvestIntroArgs::class, listOf(tabs, 2, 3)),
        )
    }

    @Test
    fun `any other backup returns to the tabs`() =
        runTest {
            useCase()

            coVerify { flag.store(true) }
            verify { router.backToRoot() }
        }
}
