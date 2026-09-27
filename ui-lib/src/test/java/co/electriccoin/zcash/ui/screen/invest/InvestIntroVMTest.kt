package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupReturnRoute
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.home.backup.WalletBackupDetail
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeArgs
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroState
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroVM
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvestIntroVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `without a backed-up phrase the button opens the existing backup flow`() =
        runTest {
            val fixture = fixture(backedUp = false)

            val state = fixture.state()
            assertEquals(stringRes(R.string.invest_intro_backup_first), state.primaryButton.text)
            state.primaryButton.onClick()
            advanceUntilIdle()

            verify { fixture.router.forward(WalletBackupDetail(isOpenedFromSeedBackupInfo = false)) }
            assertEquals(InvestIntroArgs::class, returnRoute.route)
            assertEquals(0, fixture.repo.refreshHoldingsCalls)

            // The backup flow saves the phrase and comes back here: setup carries on by itself.
            backedUpFlag.value = true
            advanceUntilIdle()
            assertEquals(1, fixture.repo.refreshHoldingsCalls)
            verify { fixture.router.replace(InvestHomeArgs) }
        }

    @Test
    fun `setup signs in, records setup and opens Invest home`() =
        runTest {
            val fixture = fixture(backedUp = true)

            val state = fixture.state()
            assertEquals(stringRes(R.string.invest_intro_setup_hint_tor), state.hint)
            state.primaryButton.onClick()
            advanceUntilIdle()

            assertEquals(1, fixture.repo.refreshHoldingsCalls)
            assertTrue(fixture.settings.settings.value.setupComplete)
            verify { fixture.router.replace(InvestHomeArgs) }
        }

    @Test
    fun `setup failures are worded by cause and setup is not recorded`() =
        runTest {
            mapOf(
                InvestApiException.ClockSkew(null) to R.string.invest_error_clock,
                InvestApiException.TorUnavailable(IOException()) to R.string.invest_error_tor,
                InvestApiException.LoginRefused(null, null) to R.string.invest_error_generic,
            ).forEach { (error, message) ->
                val fixture = fixture(backedUp = true)
                fixture.repo.onRefreshHoldings = { throw error }

                fixture.state().primaryButton.onClick()
                advanceUntilIdle()

                val state = fixture.vm.state.value
                assertNotNull(state)
                assertEquals(stringRes(message), state.errorText)
                assertFalse(state.isSettingUp)
                assertFalse(fixture.settings.settings.value.setupComplete)
            }
        }

    private inner class Fixture(
        val vm: InvestIntroVM,
        val repo: FakeInvestRepository,
        val settings: FakeInvestSettingsRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        fun state(): InvestIntroState {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.advanceUntilIdle()
            return assertNotNull(vm.state.value)
        }
    }

    private val returnRoute = WalletBackupReturnRoute()
    private val backedUpFlag = MutableStateFlow(false)

    private fun TestScope.fixture(backedUp: Boolean): Fixture {
        backedUpFlag.value = backedUp
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        val settings = FakeInvestSettingsRepository(InvestSettings(countryCode = "ID"))
        val router = mockk<NavigationRouter>(relaxed = true)
        val backup =
            mockk<WalletBackupFlagStorageProvider>().also {
                every { it.observe() } returns backedUpFlag
            }
        val tor = mockk<IsTorEnabledUseCase>().also { every { it.observe() } returns MutableStateFlow(true) }
        val vm = InvestIntroVM(repo, settings, backup, returnRoute, tor, router)
        return Fixture(vm, repo, settings, router, this)
    }
}
