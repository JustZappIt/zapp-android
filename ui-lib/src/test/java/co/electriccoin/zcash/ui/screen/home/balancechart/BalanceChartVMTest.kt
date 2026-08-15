package co.electriccoin.zcash.ui.screen.home.balancechart

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.pricing.usecase.ObservePortfolioHistoryUseCase
import co.electriccoin.zcash.ui.common.pricing.usecase.PortfolioHistoryState
import co.electriccoin.zcash.ui.common.provider.IsPortfolioChartEnabledProvider
import co.electriccoin.zcash.ui.common.usecase.BalanceHistory
import co.electriccoin.zcash.ui.common.usecase.BalanceHistoryPoint
import co.electriccoin.zcash.ui.common.usecase.GetBalanceHistoryUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MaxLineLength")
class BalanceChartVMTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun zero_balance_is_hidden_and_makes_zero_pricing_requests() =
        runTest {
            val portfolio = mockk<ObservePortfolioHistoryUseCase>()
            val vm = createVm(MutableStateFlow(BalanceHistory.Reconciled(emptyList(), Zatoshi(0))), portfolio = portfolio)
            val collection = backgroundScope.launch { vm.state.collect() }
            runCurrent()

            assertEquals(BalanceChartState.Hidden, vm.state.value)
            verify(exactly = 0) { portfolio.observe(any(), any(), any()) }
            collection.cancel()
        }

    @Test
    fun one_week_is_the_default_period() =
        runTest {
            val portfolio = mockk<ObservePortfolioHistoryUseCase>()
            every { portfolio.observe(any(), any(), any()) } returns flowOf(PortfolioHistoryState.Loading)
            val vm = createVm(MutableStateFlow(eligibleHistory()), portfolio = portfolio)
            val collection = backgroundScope.launch { vm.state.collect() }
            runCurrent()

            verify { portfolio.observe(any(), BalanceChartPeriod.W1, any()) }
            collection.cancel()
        }

    @Test
    fun disabling_fiat_toggle_cancels_pricing_and_restores_zec_chart() =
        runTest {
            val enabled = MutableStateFlow(true)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val portfolio = mockk<ObservePortfolioHistoryUseCase>()
            every { portfolio.observe(any(), any(), any()) } returns
                flow {
                    started.complete(Unit)
                    try {
                        emit(PortfolioHistoryState.Loading)
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val vm = createVm(MutableStateFlow(eligibleHistory()), enabled, portfolio)
            val collection = backgroundScope.launch { vm.state.collect() }
            started.await()

            enabled.value = false
            runCurrent()
            cancelled.await()

            assertTrue(vm.state.value is BalanceChartState.ZecData)
            collection.cancel()
        }

    @Test
    fun eligible_balance_updates_do_not_restart_the_pricing_flow() =
        runTest {
            val history = MutableStateFlow<BalanceHistory?>(eligibleHistory())
            var starts = 0
            var cancellations = 0
            val portfolio = mockk<ObservePortfolioHistoryUseCase>()
            every { portfolio.observe(any(), any(), any()) } answers {
                flow {
                    starts++
                    try {
                        emit(PortfolioHistoryState.Loading)
                        awaitCancellation()
                    } finally {
                        cancellations++
                    }
                }
            }
            val vm = createVm(history, portfolio = portfolio)
            val collection = backgroundScope.launch { vm.state.collect() }
            runCurrent()

            history.value = eligibleHistory(balance = 200_000_000L)
            runCurrent()

            assertEquals(1, starts)
            assertEquals(0, cancellations)
            collection.cancel()
        }

    private fun createVm(
        history: MutableStateFlow<BalanceHistory?>,
        enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        portfolio: ObservePortfolioHistoryUseCase,
    ): BalanceChartVM {
        val getHistory = mockk<GetBalanceHistoryUseCase>()
        every { getHistory.observe() } returns history
        val preference = mockk<IsPortfolioChartEnabledProvider>()
        every { preference.observe() } returns enabled
        return BalanceChartVM(getHistory, preference, portfolio)
    }

    private fun eligibleHistory(balance: Long = 100_000_000L): BalanceHistory.Reconciled =
        BalanceHistory.Reconciled(
            points = listOf(BalanceHistoryPoint(Instant.parse("2026-08-01T00:00:00Z"), Zatoshi(balance))),
            confirmedBalance = Zatoshi(balance),
        )
}
