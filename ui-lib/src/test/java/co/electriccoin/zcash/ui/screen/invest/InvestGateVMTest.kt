package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.invest.gate.InvestGateState
import co.electriccoin.zcash.ui.screen.invest.gate.InvestGateVM
import co.electriccoin.zcash.ui.screen.invest.gate.InvestUnavailableArgs
import co.electriccoin.zcash.ui.screen.invest.gate.ResidenceHint
import co.electriccoin.zcash.ui.screen.invest.gate.ResidenceHintSource
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeArgs
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvestGateVMTest {
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() = Locale.setDefault(Locale.US)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `suggests the SIM country and needs the attestation before an eligible country continues to the intro`() =
        runTest {
            val fixture = fixture(hint = ResidenceHint("ID", ResidenceHintSource.SIM))

            val initial = fixture.state()
            assertEquals("Indonesia", initial.countryName)
            assertEquals(R.string.invest_gate_suggested_sim, initial.suggestion.resId())
            assertNull(initial.qualifiedInvestor)
            assertFalse(initial.primaryButton.isEnabled)

            initial.attestation!!.onClick()
            advanceUntilIdle()
            val attested = fixture.vm.state.value
            assertTrue(attested.primaryButton.isEnabled)

            attested.primaryButton.onClick()
            advanceUntilIdle()
            assertEquals(listOf("ID" to false), fixture.settings.residenceCalls)
            verify { fixture.router.replace(InvestIntroArgs) }
        }

    @Test
    fun `a restricted country without the qualified-investor box is saved and lands on not available`() =
        runTest {
            val fixture = fixture(hint = ResidenceHint("DE", ResidenceHintSource.LOCALE))

            val state = fixture.state()
            assertNotNull(state.qualifiedInvestor)
            state.attestation!!.onClick()
            advanceUntilIdle()
            fixture.vm.state.value.primaryButton
                .onClick()
            advanceUntilIdle()

            assertEquals(listOf("DE" to false), fixture.settings.residenceCalls)
            verify { fixture.router.replace(InvestUnavailableArgs("DE")) }
        }

    @Test
    fun `a restricted country with both boxes continues to the intro`() =
        runTest {
            val fixture = fixture(hint = ResidenceHint("GB", ResidenceHintSource.SIM))

            val state = fixture.state()
            state.attestation!!.onClick()
            state.qualifiedInvestor!!.onClick()
            advanceUntilIdle()
            fixture.vm.state.value.primaryButton
                .onClick()
            advanceUntilIdle()

            assertEquals(listOf("GB" to true), fixture.settings.residenceCalls)
            verify { fixture.router.replace(InvestIntroArgs) }
        }

    @Test
    fun `a prohibited country asks for no attestation and goes straight to not available`() =
        runTest {
            val fixture = fixture(hint = ResidenceHint("US", ResidenceHintSource.SIM))

            val state = fixture.state()
            assertNull(state.attestation)
            assertNull(state.qualifiedInvestor)
            assertTrue(state.primaryButton.isEnabled)

            state.primaryButton.onClick()
            advanceUntilIdle()

            assertEquals(listOf("US" to false), fixture.settings.residenceCalls)
            verify { fixture.router.replace(InvestUnavailableArgs("US")) }
        }

    @Test
    fun `an already set-up user re-confirming goes to Invest home`() =
        runTest {
            val fixture = fixture(hint = null, settings = InvestSettings(countryCode = "ID", setupComplete = true))

            val state = fixture.state()
            assertEquals("Indonesia", state.countryName)
            assertNull(state.suggestion)
            state.attestation!!.onClick()
            advanceUntilIdle()
            fixture.vm.state.value.primaryButton
                .onClick()
            advanceUntilIdle()

            verify { fixture.router.replace(InvestHomeArgs) }
        }

    @Test
    fun `the picker searches by name, and picking a new country clears the ticks`() =
        runTest {
            val fixture = fixture(hint = ResidenceHint("ID", ResidenceHintSource.SIM))

            fixture.state().attestation!!.onClick()
            fixture.vm.state.value
                .onChangeCountry()
            advanceUntilIdle()
            fixture.vm.state.value.picker!!
                .onQueryChange("germ")
            advanceUntilIdle()

            val picker = fixture.vm.state.value.picker!!
            assertEquals(listOf("DE"), picker.items.map { it.code })
            picker.items.single().onClick()
            advanceUntilIdle()

            val after = fixture.vm.state.value
            assertNull(after.picker)
            assertEquals("Germany", after.countryName)
            assertNull(after.suggestion)
            assertFalse(after.attestation!!.isChecked)
            assertNotNull(after.qualifiedInvestor)
        }

    @Test
    fun `no hint and nothing saved leaves the country to pick`() =
        runTest {
            val fixture = fixture(hint = null)

            val state = fixture.state()

            assertNull(state.countryName)
            assertNull(state.attestation)
            assertFalse(state.primaryButton.isEnabled)
        }

    private class Fixture(
        val vm: InvestGateVM,
        val settings: FakeInvestSettingsRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        fun state(): InvestGateState {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.advanceUntilIdle()
            return vm.state.value
        }
    }

    private fun TestScope.fixture(
        hint: ResidenceHint?,
        settings: InvestSettings = InvestSettings(),
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settingsRepo = FakeInvestSettingsRepository(settings)
        val router = mockk<NavigationRouter>(relaxed = true)
        val vm = InvestGateVM(settingsRepo, { hint }, router)
        return Fixture(vm, settingsRepo, router, this)
    }

    private fun StringResource?.resId(): Int? = (this as? StringResource.ByResource)?.resource
}
