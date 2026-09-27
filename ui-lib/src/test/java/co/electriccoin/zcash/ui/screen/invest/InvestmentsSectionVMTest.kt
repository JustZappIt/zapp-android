package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.gate.InvestGateArgs
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeArgs
import co.electriccoin.zcash.ui.screen.invest.section.InvestPayState
import co.electriccoin.zcash.ui.screen.invest.section.InvestmentsSectionState
import co.electriccoin.zcash.ui.screen.invest.section.InvestmentsSectionVM
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MaxLineLength")
class InvestmentsSectionVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the flag off hides the block and the speed-dial action without touching the repository`() =
        runTest {
            val fixture = fixture(isEnabled = false, settings = READY)

            val state = fixture.state()

            assertEquals(InvestPayState.HIDDEN, state)
            assertEquals(0, fixture.repo.refreshHoldingsCalls)
        }

    @Test
    fun `a prohibited country hides everything Invest`() =
        runTest {
            val fixture = fixture(settings = InvestSettings(countryCode = "US"))

            val state = fixture.state()

            assertIs<InvestmentsSectionState.Hidden>(state.section)
            assertFalse(state.isSpeedDialActionVisible)
        }

    @Test
    fun `a Keystone account sees nothing Invest and nothing is fetched for it`() =
        runTest {
            val fixture = fixture(settings = READY, accountSupported = false)
            fixture.repo.holdings.value = HOLDINGS

            assertEquals(InvestPayState.HIDDEN, fixture.state())
            assertEquals(0, fixture.repo.refreshHoldingsCalls)
        }

    @Test
    fun `before the gate the block is the entry card and the action is offered`() =
        runTest {
            val fixture = fixture(settings = InvestSettings())

            val state = fixture.state()

            assertIs<InvestmentsSectionState.Entry>(state.section)
            assertTrue(state.isSpeedDialActionVisible)
            assertEquals(0, fixture.repo.refreshHoldingsCalls)
        }

    @Test
    fun `a restricted country without the attestation still gets the entry card, which reopens the gate`() =
        runTest {
            val fixture = fixture(settings = InvestSettings(countryCode = "DE", qualifiedInvestor = false))

            val state = fixture.state()
            (state.section as InvestmentsSectionState.Entry).onClick()
            advanceUntilIdle()

            assertTrue(state.isSpeedDialActionVisible)
            verify { fixture.router.forward(InvestGateArgs) }
        }

    @Test
    fun `available but not set up shows the entry card`() =
        runTest {
            val fixture = fixture(settings = InvestSettings(countryCode = "ID"))

            assertIs<InvestmentsSectionState.Entry>(fixture.state().section)
        }

    @Test
    fun `set up with holdings shows value first, shares second and the total, and refreshes once`() =
        runTest {
            val fixture = fixture(settings = READY)
            fixture.repo.holdings.value = HOLDINGS

            val section = assertIs<InvestmentsSectionState.Holdings>(fixture.state().section)

            assertEquals(1, fixture.repo.refreshHoldingsCalls)
            assertEquals(stringRes("$98.99"), section.rows.single().value)
            assertEquals(stringRes("0.4410 NVDA"), section.rows.single().units)
            assertEquals(stringRes("$98.99"), section.total)
            assertFalse(section.isStale)
            section.onHeaderClick()
            advanceUntilIdle()
            verify { fixture.router.forward(InvestHomeArgs) }
        }

    @Test
    fun `a failed refresh keeps the cached holdings and marks them stale`() =
        runTest {
            val fixture = fixture(settings = READY)
            fixture.repo.holdings.value = HOLDINGS
            fixture.repo.onRefreshHoldings = { throw InvestApiException.Unreachable(IOException()) }

            val section = assertIs<InvestmentsSectionState.Holdings>(fixture.state().section)

            assertTrue(section.isStale)
        }

    @Test
    fun `a failed first load with nothing cached offers a retry`() =
        runTest {
            val fixture = fixture(settings = READY)
            fixture.repo.onRefreshHoldings = { throw InvestApiException.ClockSkew(null) }

            val section = assertIs<InvestmentsSectionState.Error>(fixture.state().section)

            fixture.repo.onRefreshHoldings = { fixture.repo.holdings.value = HOLDINGS }
            section.onRetry()
            advanceUntilIdle()
            assertIs<InvestmentsSectionState.Holdings>(fixture.vm.state.value.section)
        }

    @Test
    fun `nothing held yet shows the entry card`() =
        runTest {
            val fixture = fixture(settings = READY)
            fixture.repo.holdings.value = HOLDINGS.copy(items = emptyList(), totalUsd = null)

            assertIs<InvestmentsSectionState.Entry>(fixture.state().section)
        }

    private class Fixture(
        val vm: InvestmentsSectionVM,
        val repo: FakeInvestRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        fun state(): InvestPayState {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.advanceUntilIdle()
            return vm.state.value
        }
    }

    private fun TestScope.fixture(
        isEnabled: Boolean = true,
        settings: InvestSettings,
        accountSupported: Boolean = true,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository().also { it.accountSupported = accountSupported }
        val settingsRepo = FakeInvestSettingsRepository(settings)
        val router = mockk<NavigationRouter>(relaxed = true)
        val vm =
            InvestmentsSectionVM(
                investRepository = repo,
                settingsRepository = settingsRepo,
                accountDataSource = mockk<AccountDataSource>().also { every { it.selectedAccount } returns flowOf(mockk<WalletAccount>()) },
                currencyProvider = USD_CURRENCY,
                navigateToInvest = NavigateToInvestUseCase(settingsRepo, router),
                isInvestEnabled = isEnabled,
            )
        return Fixture(vm, repo, router, this)
    }

    private companion object {
        val READY = InvestSettings(countryCode = "ID", setupComplete = true)
        val HOLDINGS =
            Holdings(
                items = listOf(Holding(InvestAssets.curated.first(), BigDecimal("0.44109"), BigDecimal("98.99"))),
                totalUsd = BigDecimal("98.99"),
                updatedAt = Instant.fromEpochMilliseconds(START_MILLIS),
                isStale = false,
            )
    }
}
