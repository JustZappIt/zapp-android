package co.electriccoin.zcash.ui.screen.invest

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.BridgeAuthorizationCancelledException
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyState
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyVM
import co.electriccoin.zcash.ui.screen.invest.buy.InvestReviewState
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class InvestBuyVMTest {
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() = Locale.setDefault(Locale.US)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `the estimate waits until typing has stopped for 500 ms and then quotes only the last amount`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }

            fixture.type("10")
            advanceTimeBy(200)
            fixture.type("100")
            advanceTimeBy(InvestBuyVM.AMOUNT_SETTLE_DELAY_MS - 1)
            runCurrent()
            assertTrue(fixture.repo.estimateCalls.isEmpty())
            assertEquals(stringRes(R.string.invest_buy_quote_loading), fixture.vm.state.value.notice)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(BigDecimal("100")), fixture.repo.estimateCalls)
        }

    @Test
    fun `a priced estimate fills the ledger value first and enables Review`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }

            fixture.type("100")
            advanceUntilIdle()

            val state = fixture.vm.state.value
            val ledger = assertNotNull(state.ledger)
            assertEquals(stringRes("0.06478 ZEC"), ledger.youSend)
            assertEquals(stringRes(R.string.invest_buy_you_get_value, "$99.01", "0.4410 NVDA"), ledger.youGet)
            assertEquals(stringRes(R.string.invest_buy_fee_value, "$0.95", "0.95"), ledger.fees)
            assertEquals(stringRes(R.string.invest_buy_eta_value, 8), ledger.eta)
            assertNull(state.notice)
            assertNull(state.noPrice)
            assertTrue(state.primaryButton.isEnabled)
        }

    @Test
    fun `in the user's currency, amounts are typed and shown locally and 1Click is asked in USD`() =
        runTest {
            val fixture = fixture(currency = EUR_CURRENCY)
            fixture.repo.onEstimate = { _, _ -> PRICED }

            val state = fixture.vm.state.value
            assertEquals("€", state.currencySymbol)
            assertEquals(listOf("€37", "€92", "€230"), state.presets.take(3).map { (it.label as StringResource.ByString).value })

            fixture.type("100")
            advanceUntilIdle()

            // €100 ÷ 0.92, to the cent and never above what was typed
            assertEquals(listOf(BigDecimal("108.69")), fixture.repo.estimateCalls)
            assertEquals(
                stringRes(R.string.invest_buy_you_get_value, "€91.09", "0.4410 NVDA"),
                fixture.vm.state.value.ledger
                    ?.youGet,
            )
        }

    @Test
    fun `while a sale of the stock is in progress, Review is off and the screen says why`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.pendingTrades.value = listOf(PendingTrade("0xsale", NVIDIA.assetId, isSale = true))

            fixture.type("100")
            advanceUntilIdle()

            val inProgress = assertNotNull(fixture.vm.state.value.tradeInProgress)
            assertEquals(stringRes(R.string.invest_trade_in_flight, "NVIDIA"), inProgress.text)
            assertFalse(fixture.vm.state.value.primaryButton.isEnabled)
            // The note leads to the trade holding things up, where a stuck one can go to support or be dismissed.
            inProgress.onOpen()
            verify { fixture.router.forward(EXPECTED_ROUTE) }
        }

    @Test
    fun `no price, returned or thrown, swaps the ledger for the no-price card and Try again quotes once more`() =
        runTest {
            listOf<suspend () -> BuyEstimate>(
                { BuyEstimate.NoPrice },
                { throw InvestApiException.NoPrice("corr") },
            ).forEach { answer ->
                val fixture = fixture()
                fixture.repo.onEstimate = { _, _ -> answer() }

                fixture.type("100")
                advanceUntilIdle()

                val state = fixture.vm.state.value
                assertNull(state.ledger)
                val noPrice = assertNotNull(state.noPrice)
                assertFalse(state.primaryButton.isEnabled)

                fixture.repo.onEstimate = { _, _ -> PRICED }
                noPrice.onTryAgain()
                advanceUntilIdle()
                assertEquals(2, fixture.repo.estimateCalls.size)
                assertNull(fixture.vm.state.value.noPrice)
                assertTrue(fixture.vm.state.value.primaryButton.isEnabled)
            }
        }

    @Test
    fun `below the minimum says so without asking 1Click`() =
        runTest {
            val fixture = fixture()

            fixture.type("20")
            advanceUntilIdle()

            val state = fixture.vm.state.value
            assertTrue(fixture.repo.estimateCalls.isEmpty())
            assertEquals(stringRes(R.string.invest_buy_below_minimum, "$40"), state.notice)
            assertFalse(state.isNoticeDanger)
            assertFalse(state.primaryButton.isEnabled)
        }

    @Test
    fun `not enough shielded ZEC marks the amount and blocks Review`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> BuyEstimate.InsufficientZec(BigDecimal("0.01")) }

            fixture.type("500")
            advanceUntilIdle()

            val state = fixture.vm.state.value
            assertEquals(stringRes(R.string.invest_buy_insufficient, "0.01 ZEC"), state.notice)
            assertTrue(state.isNoticeDanger)
            assertTrue(state.isAmountError)
            assertFalse(state.primaryButton.isEnabled)
        }

    @Test
    fun `a clock-skew failure is worded for the user`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> throw InvestApiException.ClockSkew("corr") }

            fixture.type("100")
            advanceUntilIdle()

            assertEquals(stringRes(R.string.invest_error_clock), fixture.vm.state.value.notice)
        }

    @Test
    fun `presets set the amount, and Max leaves room for the fee`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }

            val presets = fixture.vm.state.value.presets
            assertEquals(listOf("$40", "$100", "$250"), presets.take(3).map { (it.label as StringResource.ByString).value })
            presets[1].onClick()
            advanceUntilIdle()
            assertEquals(listOf(BigDecimal(100)), fixture.repo.estimateCalls)

            // (1 ZEC − 0.0005) × $50 × 0.98 = $48.97
            fixture.vm.state.value.presets
                .last()
                .onClick()
            advanceUntilIdle()
            assertEquals(BigDecimal("48.97"), fixture.repo.estimateCalls.last())
        }

    @Test
    fun `the review sheet counts down the held price and offers a refresh at zero`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }

            val review = fixture.openReview()
            assertEquals(stringRes("10:00"), review.countdown)
            assertEquals(stringRes(R.string.invest_review_confirm), review.primaryButton.text)
            assertEquals(stringRes(R.string.invest_review_privacy, "0.00032 ZEC"), review.privacy)
            assertEquals(stringRes(R.string.invest_buy_you_get_value_exact, "$98.02", "0.4366 NVDA"), review.atLeast)

            advanceTimeBy(18_000)
            runCurrent()
            assertEquals(stringRes("9:42"), fixture.reviewState()!!.countdown)

            advanceTimeBy(10.minutes.inWholeMilliseconds)
            runCurrent()
            val expired = fixture.reviewState()!!
            assertTrue(expired.isExpired)
            assertEquals(stringRes(R.string.invest_review_refresh), expired.primaryButton.text)

            expired.primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, fixture.repo.prepareCalls.size)
            val refreshed = fixture.reviewState()!!
            assertFalse(refreshed.isExpired)
            assertEquals(stringRes("10:00"), refreshed.countdown)
            assertTrue(fixture.repo.executeCalls.isEmpty())
        }

    @Test
    fun `a cancelled authentication leaves the user on the sheet with nothing sent`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            fixture.repo.onExecute = { throw BridgeAuthorizationCancelledException() }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            val review = assertNotNull(fixture.reviewState())
            assertFalse(review.isBusy)
            assertNull(review.errorText)
            assertTrue(review.primaryButton.isEnabled)
            assertEquals(1, fixture.repo.executeCalls.size)
            verify(exactly = 0) { fixture.router.replace(any()) }
        }

    @Test
    fun `tapping Review and Confirm twice prepares and pays once`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ ->
                delay(1_000)
                preparedBuy(asset, fixture.now() + 10.minutes)
            }
            fixture.repo.onExecute = {
                delay(1_000)
                "t1deposit"
            }

            fixture.type("100")
            advanceTimeBy(InvestBuyVM.AMOUNT_SETTLE_DELAY_MS + 1)
            runCurrent()
            val review = fixture.vm.state.value.primaryButton
            review.onClick()
            review.onClick()
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(1, fixture.repo.prepareCalls.size)

            val confirm = fixture.reviewState()!!.primaryButton
            confirm.onClick()
            confirm.onClick()
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(1, fixture.repo.executeCalls.size)
        }

    @Test
    fun `an uncertain failure for a buy the engine now lists opens its progress, with the USD from review`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            fixture.repo.onExecute = {
                fixture.repo.pendingTrades.value = listOf(PendingTrade("t1deposit", NVIDIA.assetId, isSale = false))
                throw IOException("the broadcast went quiet")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            verify { fixture.router.replace(InvestProgressArgs("t1deposit", NVIDIA.assetId, "100")) }
        }

    @Test
    fun `an uncertain failure with nothing recorded closes the sheet and says it didn't complete`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            fixture.repo.onExecute = { throw IOException("no route") }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            assertEquals(stringRes(R.string.invest_review_failed), fixture.vm.state.value.notice)
            verify(exactly = 0) { fixture.router.replace(any()) }
        }

    @Test
    fun `a refusal because another trade of the stock is pending keeps the sheet with Confirm off`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            fixture.repo.onExecute = {
                fixture.repo.pendingTrades.value = listOf(PendingTrade("0xsale", NVIDIA.assetId, isSale = true))
                error("A sale of this stock is still in progress")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            val review = assertNotNull(fixture.reviewState())
            assertEquals(stringRes(R.string.invest_trade_in_flight, "NVIDIA"), review.errorText)
            assertFalse(review.primaryButton.isEnabled)
        }

    @Test
    fun `a price that lapses during the prompt asks for a fresh one instead of reporting a failure`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            fixture.repo.onExecute = {
                delay(11.minutes.inWholeMilliseconds)
                error("The price is no longer held; prepare the buy again")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(11.minutes.inWholeMilliseconds + 100)
            runCurrent()

            val review = assertNotNull(fixture.reviewState())
            assertTrue(review.isExpired)
            assertNull(review.errorText)
            assertEquals(stringRes(R.string.invest_review_refresh), review.primaryButton.text)
        }

    @Test
    fun `Confirm checks the clock, not the last countdown tick`() =
        runTest {
            val clock = SkewedClock(virtualClock())
            val fixture = fixture(clock = clock)
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }

            val review = fixture.openReview()
            // The phone's clock moves past the deadline before the countdown's next tick has run.
            clock.skew = 11.minutes
            review.primaryButton.onClick()
            runCurrent()

            assertTrue(fixture.repo.executeCalls.isEmpty())
            assertTrue(fixture.reviewState()!!.isExpired)
        }

    @Test
    fun `when the trade records can't be read, Review is off and support is the way out`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.pendingTrades.value = null

            fixture.type("100")
            advanceUntilIdle()

            val blocked = assertNotNull(fixture.vm.state.value.tradeInProgress)
            assertEquals(stringRes(R.string.invest_trades_unreadable), blocked.text)
            assertEquals(stringRes(R.string.invest_contact_support), blocked.actionLabel)
            assertFalse(fixture.vm.state.value.primaryButton.isEnabled)
            blocked.onOpen()
            verify {
                fixture.router.forward(
                    SupportChatArgs(prefilledMessage = "Invest can't read its trade records on my phone."),
                )
            }
        }

    @Test
    fun `a new exchange rate asks again for the same typed amount`() =
        runTest {
            val rates = MutableStateFlow(InvestCurrency.USD)
            val fixture = fixture(currency = { rates })
            fixture.repo.onEstimate = { _, _ -> PRICED }

            fixture.type("100")
            advanceUntilIdle()
            rates.value = InvestCurrency("EUR", "€", BigDecimal("0.92"))
            advanceUntilIdle()

            assertEquals(listOf(BigDecimal("100"), BigDecimal("108.69")), fixture.repo.estimateCalls)
        }

    @Test
    fun `a confirmed buy opens its progress screen`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            fixture.repo.onExecute = { "t1deposit" }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            verify { fixture.router.replace(InvestProgressArgs("t1deposit", NVIDIA.assetId, "100")) }
        }

    @Test
    fun `a failed prepare reporting no price shows the no-price card instead of a sheet`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { _, _ -> throw InvestApiException.NoPrice(null) }

            fixture.type("100")
            advanceUntilIdle()
            fixture.vm.state.value.primaryButton
                .onClick()
            advanceUntilIdle()

            assertNull(fixture.reviewState())
            assertNotNull(fixture.vm.state.value.noPrice)
        }

    private inner class Fixture(
        val vm: InvestBuyVM,
        val repo: FakeInvestRepository,
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

        fun reviewState(): InvestReviewState? = vm.reviewState.value

        fun openReview(): InvestReviewState {
            type("100")
            scope.advanceTimeBy(InvestBuyVM.AMOUNT_SETTLE_DELAY_MS + 1)
            scope.runCurrent()
            val state: InvestBuyState = vm.state.value
            state.primaryButton.onClick()
            scope.runCurrent()
            return assertNotNull(vm.reviewState.value)
        }
    }

    private fun TestScope.fixture(
        currency: InvestCurrencyProvider = USD_CURRENCY,
        clock: Clock = virtualClock(),
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        val router = mockk<NavigationRouter>(relaxed = true)
        val account =
            mockk<WalletAccount>().also { every { it.spendableShieldedBalance } returns Zatoshi(100_000_000L) }
        val accounts =
            mockk<AccountDataSource>().also { every { it.selectedAccount } returns flowOf(account) }
        val swap =
            mockk<SwapRepository>().also {
                every { it.assets } returns
                    MutableStateFlow(
                        SwapAssetsData(
                            zecAsset =
                                mockk<SwapAsset>(relaxed = true) {
                                    every { usdPrice } returns BigDecimal(50)
                                },
                        ),
                    )
            }
        val vm =
            InvestBuyVM(
                args = InvestBuyArgs(NVIDIA.assetId),
                investRepository = repo,
                accountDataSource = accounts,
                swapRepository = swap,
                currencyProvider = currency,
                navigationRouter = router,
                clock = clock,
            )
        return Fixture(vm, repo, router, this)
    }

    private companion object {
        val EXPECTED_ROUTE =
            InvestSellProgressArgs("0xsale", InvestAssets.curated.first { it.ticker == "NVDA" }.assetId)
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
        val PRICED =
            BuyEstimate.Priced(
                zecIn = BigDecimal("0.06478"),
                unitsOut = BigDecimal("0.4410"),
                usdOut = BigDecimal("99.01"),
                feesUsd = BigDecimal("0.95"),
                etaSeconds = 470,
                refundFeeZec = BigDecimal("0.00032"),
            )
    }
}
