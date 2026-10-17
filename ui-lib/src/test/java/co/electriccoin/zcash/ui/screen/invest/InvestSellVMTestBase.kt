package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellArgs
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellReviewState
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellVM
import co.electriccoin.zcash.ui.screen.invest.sell.SellAmountMode
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.justzappit.evm.intents.IntentTransferSigner
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MaxLineLength")
internal abstract class InvestSellVMTestBase {
    protected val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() = Locale.setDefault(Locale.US)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(defaultLocale)
    }

    protected inner class Fixture(
        val vm: InvestSellVM,
        val repo: FakeInvestRepository,
        val sell: FakeInvestSellRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.reviewState.collect {} }
            scope.runCurrent()
        }

        fun now(): Instant = Instant.fromEpochMilliseconds(START_MILLIS + scope.testScheduler.currentTime)

        fun type(text: String) {
            vm.state.value.amountInput
                .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal(text)))
        }

        fun reviewState(): InvestSellReviewState? = vm.reviewState.value

        fun openReview(): InvestSellReviewState {
            type("98.99")
            scope.advanceTimeBy(InvestSellVM.AMOUNT_SETTLE_DELAY_MS + 1)
            scope.runCurrent()
            vm.state.value.primaryButton
                .onClick()
            scope.runCurrent()
            return assertNotNull(vm.reviewState.value)
        }
    }

    protected fun TestScope.fixture(
        currency: InvestCurrencyProvider = USD_CURRENCY,
        clock: Clock = virtualClock(),
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        repo.holdings.value =
            Holdings(
                items = listOf(Holding(NVIDIA, BigDecimal("0.4410"), BigDecimal("98.99"))),
                totalUsd = BigDecimal("98.99"),
                updatedAt = Instant.fromEpochMilliseconds(START_MILLIS),
                isStale = false,
            )
        val sell = FakeInvestSellRepository()
        val router = mockk<NavigationRouter>(relaxed = true)
        val swap =
            mockk<SwapRepository>().also {
                every { it.assets } returns
                    MutableStateFlow(
                        SwapAssetsData(zecAsset = mockk<SwapAsset>(relaxed = true) { every { usdPrice } returns ZEC_USD }),
                    )
            }
        val vm =
            InvestSellVM(
                args = InvestSellArgs(NVIDIA.assetId),
                investRepository = repo,
                sellRepository = sell,
                swapRepository = swap,
                currencyProvider = currency,
                tradeFollower = FakeInvestTradeFollower(),
                navigationRouter = router,
                clock = clock,
            )
        return Fixture(vm, repo, sell, router, this)
    }

    protected companion object {
        val EXPECTED_ROUTE = InvestProgressArgs("0xother", InvestAssets.curated.first { it.ticker == "NVDA" }.assetId)
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
        val ZEC_USD = BigDecimal(1500)
        val PRICED =
            SellEstimate.Priced(
                unitsIn = BigDecimal("0.4410"),
                usdIn = BigDecimal("98.99"),
                zecOut = BigDecimal("0.0634"),
                usdOut = BigDecimal("97.87"),
                feesUsd = BigDecimal("1.12"),
                withdrawFeeZec = BigDecimal("0.00064"),
                etaSeconds = 140,
            )
    }
}
