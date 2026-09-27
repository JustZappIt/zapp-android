package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.PendingTrade
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellArgs
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellReviewState
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellVM
import co.electriccoin.zcash.ui.screen.invest.sell.SellAmountMode
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MaxLineLength")
class InvestSellVMTest {
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() = Locale.setDefault(Locale.US)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `shares are quoted once typing has stopped for 500 ms`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.vm.state.value
                .onModeChange(SellAmountMode.SHARES)

            fixture.type("0.1")
            advanceTimeBy(200)
            fixture.type("0.2")
            advanceTimeBy(InvestSellVM.AMOUNT_SETTLE_DELAY_MS - 1)
            runCurrent()
            assertTrue(fixture.sell.estimateCalls.isEmpty())

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf<SellAmount>(SellAmount.Units(BigDecimal("0.2"))), fixture.sell.estimateCalls)
            assertEquals("NVDA", fixture.vm.state.value.amountSymbol)
        }

    @Test
    fun `money is typed in the user's currency and asked for in USD`() =
        runTest {
            val fixture = fixture(currency = EUR_CURRENCY)
            fixture.sell.onEstimate = { _, _ -> PRICED }

            fixture.type("50")
            advanceUntilIdle()

            // €50 ÷ 0.92, to the cent and never above what was typed
            assertEquals(listOf<SellAmount>(SellAmount.Usd(BigDecimal("54.34"))), fixture.sell.estimateCalls)
            assertEquals("€", fixture.vm.state.value.amountSymbol)
        }

    @Test
    fun `a priced estimate shows value first, the ZEC, the fees with the fixed withdrawal fee, and enables Review`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }

            fixture.type("98.99")
            advanceUntilIdle()

            val state = fixture.vm.state.value
            val ledger = assertNotNull(state.ledger)
            assertEquals(stringRes(R.string.invest_buy_you_get_value_exact, "$98.99", "0.4410 NVDA"), ledger.youSell)
            assertEquals(stringRes(R.string.invest_sell_you_get_value, "0.0634 ZEC"), ledger.youGet)
            assertEquals(stringRes("$1.12"), ledger.fees)
            assertEquals(stringRes(R.string.invest_sell_fee_note, "0.00064 ZEC"), ledger.feeNote)
            assertTrue(state.primaryButton.isEnabled)
            assertNull(state.sellAllSuggestion)
        }

    @Test
    fun `the refusals each say why and block Review`() =
        runTest {
            val cases =
                listOf(
                    SellEstimate.BelowMinimum(BigDecimal(40)) to
                        (stringRes(R.string.invest_buy_below_minimum, "$40") to false),
                    SellEstimate.ExceedsHolding(BigDecimal("0.4410")) to
                        (stringRes(R.string.invest_sell_exceeds, "0.4410 NVDA") to true),
                    SellEstimate.NothingHeld to (stringRes(R.string.invest_sell_nothing_held, "NVIDIA") to true),
                    SellEstimate.TooSmallToSell to (stringRes(R.string.invest_sell_too_small) to true),
                )
            cases.forEach { (estimate, expected) ->
                val fixture = fixture()
                fixture.sell.onEstimate = { _, _ -> estimate }

                fixture.type("500")
                advanceUntilIdle()

                val state = fixture.vm.state.value
                assertEquals(expected.first, state.notice)
                assertEquals(expected.second, state.isNoticeDanger)
                assertFalse(state.primaryButton.isEnabled)
            }
        }

    @Test
    fun `no price swaps the ledger for the no-price card`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> SellEstimate.NoPrice }

            fixture.type("50")
            advanceUntilIdle()

            assertNull(fixture.vm.state.value.ledger)
            assertNotNull(fixture.vm.state.value.noPrice)
            assertFalse(fixture.vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `Sell all asks for everything, and 50 percent halves the shares`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }

            fixture.vm.state.value.presets
                .last()
                .onClick()
            advanceUntilIdle()
            assertEquals(listOf<SellAmount>(SellAmount.All), fixture.sell.estimateCalls)

            fixture.vm.state.value
                .onModeChange(SellAmountMode.SHARES)
            fixture.vm.state.value.presets
                .first()
                .onClick()
            advanceUntilIdle()
            assertEquals(SellAmount.Units(BigDecimal("0.2205")), fixture.sell.estimateCalls.last())
        }

    @Test
    fun `a partial sale that would leave less than the minimum suggests selling all`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED.copy(usdIn = BigDecimal("70")) }

            fixture.type("70")
            advanceUntilIdle()

            val suggestion = assertNotNull(fixture.vm.state.value.sellAllSuggestion)
            assertEquals(stringRes(R.string.invest_sell_all_suggestion, "$40"), suggestion.text)
            suggestion.onSellAll()
            advanceUntilIdle()
            assertEquals(SellAmount.All, fixture.sell.estimateCalls.last())
        }

    @Test
    fun `while a buy of the stock is in progress, Review is off and the screen says why`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.pending.value = listOf(PendingTrade("0xother", NVIDIA.assetId, isSale = false))

            fixture.type("98.99")
            advanceUntilIdle()

            val inProgress = assertNotNull(fixture.vm.state.value.tradeInProgress)
            assertEquals(stringRes(R.string.invest_trade_in_flight, "NVIDIA"), inProgress.text)
            assertFalse(fixture.vm.state.value.primaryButton.isEnabled)
            // The note leads to the trade holding things up, where a stuck one can go to support or be dismissed.
            inProgress.onOpen()
            verify { fixture.router.forward(EXPECTED_ROUTE) }
        }

    @Test
    fun `the review sheet says what is authorised, counts down and refreshes at zero`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }

            val review = fixture.openReview()
            assertEquals(
                stringRes(R.string.invest_sell_authorise, "0.4410 NVDA", "$98.99"),
                review.authorisation,
            )
            assertEquals(stringRes("0.0628 ZEC"), review.atLeast)
            assertEquals(stringRes("10:00"), review.countdown)
            assertFalse(review.isSignedMessageOpen)
            review.onToggleSignedMessage()
            runCurrent()
            assertTrue(fixture.reviewState()!!.isSignedMessageOpen)

            advanceTimeBy(10.minutes.inWholeMilliseconds + 1_000)
            runCurrent()
            val expired = fixture.reviewState()!!
            assertTrue(expired.isExpired)
            assertEquals(stringRes(R.string.invest_review_refresh), expired.primaryButton.text)

            expired.primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, fixture.sell.prepareCalls.size)
            assertFalse(fixture.reviewState()!!.isExpired)
            assertTrue(fixture.sell.executeCalls.isEmpty())
        }

    @Test
    fun `a cancelled biometric prompt leaves the user on the sheet`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = { throw BiometricsCancelledException() }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            val review = assertNotNull(fixture.reviewState())
            assertFalse(review.isBusy)
            assertNull(review.errorText)
            verify(exactly = 0) { fixture.router.replace(any()) }
        }

    @Test
    fun `an intent that isn't the reviewed transfer ends on something did not match`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = { throw SellIntentRefusedException(IntentTransferSigner.Rejection.WRONG_SIGNER, null) }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            assertTrue(fixture.vm.state.value.isRefused)
        }

    @Test
    fun `a refused prepare also ends on something did not match, with no sheet`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { _, _ -> throw SellIntentRefusedException(IntentTransferSigner.Rejection.EXPIRED, "c") }

            fixture.type("98.99")
            advanceUntilIdle()
            fixture.vm.state.value.primaryButton
                .onClick()
            advanceUntilIdle()

            assertNull(fixture.reviewState())
            assertTrue(fixture.vm.state.value.isRefused)
        }

    @Test
    fun `a confirmed sale opens its progress screen`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = { "0xdeposit" }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            verify { fixture.router.replace(InvestSellProgressArgs("0xdeposit", NVIDIA.assetId, "98.99")) }
        }

    private inner class Fixture(
        val vm: InvestSellVM,
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

    private fun TestScope.fixture(currency: InvestCurrencyProvider = USD_CURRENCY): Fixture {
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
        val vm =
            InvestSellVM(
                args = InvestSellArgs(NVIDIA.assetId),
                investRepository = repo,
                sellRepository = sell,
                pendingTrades = sell.pendingTrades,
                currencyProvider = currency,
                navigationRouter = router,
                clock = virtualClock(),
            )
        return Fixture(vm, sell, router, this)
    }

    private companion object {
        val EXPECTED_ROUTE = InvestProgressArgs("0xother", InvestAssets.curated.first { it.ticker == "NVDA" }.assetId)
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
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
