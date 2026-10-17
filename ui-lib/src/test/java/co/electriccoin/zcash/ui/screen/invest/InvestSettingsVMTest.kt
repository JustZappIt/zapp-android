package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.demo.InvestDemoControlsArgs
import co.electriccoin.zcash.ui.screen.invest.gate.InvestChangeCountryArgs
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsState
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsVM
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InvestSettingsVMTest {
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() = Locale.setDefault(Locale.US)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `a country where buying is offered says so`() =
        runTest {
            val state = fixture(INVEST_READY).state()

            assertEquals(stringRes("Indonesia"), state.country)
            assertEquals(stringRes(R.string.invest_settings_status_available), state.status)
        }

    @Test
    fun `a prohibited country is sell-only`() =
        runTest {
            val state = fixture(INVEST_SELL_ONLY).state()

            assertEquals(stringRes("Canada"), state.country)
            assertEquals(stringRes(R.string.invest_sell_only_banner, "Canada"), state.status)
        }

    @Test
    fun `a restricted country without the attestation is sell-only, with it buying is offered`() =
        runTest {
            assertEquals(
                stringRes(R.string.invest_sell_only_banner, "Germany"),
                fixture(InvestSettings(countryCode = "DE", setupComplete = true)).state().status,
            )
            assertEquals(
                stringRes(R.string.invest_settings_status_available),
                fixture(InvestSettings(countryCode = "DE", qualifiedInvestor = true, setupComplete = true))
                    .state()
                    .status,
            )
        }

    @Test
    fun `no country yet says how to start, and the row opens the country change`() =
        runTest {
            val fixture = fixture(InvestSettings())

            val state = fixture.state()
            assertEquals(stringRes(R.string.invest_settings_country_none), state.country)
            assertEquals(stringRes(R.string.invest_settings_status_none), state.status)

            state.onCountryClick()
            verify { fixture.router.forward(InvestChangeCountryArgs) }
        }

    @Test
    fun `a saved change shows straight away`() =
        runTest {
            val fixture = fixture(INVEST_READY)
            fixture.state()

            fixture.settings.setResidence("CA", qualifiedInvestor = false)
            advanceUntilIdle()

            assertEquals(stringRes("Canada"), assertNotNull(fixture.vm.state.value).country)
        }

    @Test
    fun `the demo build adds Demo controls, other builds don't`() =
        runTest {
            val demo = fixture(INVEST_READY, isDemo = true)
            demo.state().onDemoControlsClick!!.invoke()
            verify { demo.router.forward(InvestDemoControlsArgs) }

            assertNull(fixture(INVEST_READY).state().onDemoControlsClick)
        }

    private class Fixture(
        val vm: InvestSettingsVM,
        val settings: FakeInvestSettingsRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        fun state(): InvestSettingsState {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.advanceUntilIdle()
            return assertNotNull(vm.state.value)
        }
    }

    private fun TestScope.fixture(
        settings: InvestSettings,
        isDemo: Boolean = false,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestSettingsRepository(settings)
        val router = mockk<NavigationRouter>(relaxed = true)
        return Fixture(InvestSettingsVM(repo, router, isDemo), repo, router, this)
    }
}
