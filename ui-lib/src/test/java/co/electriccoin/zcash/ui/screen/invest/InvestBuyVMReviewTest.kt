package co.electriccoin.zcash.ui.screen.invest

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
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
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
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
internal class InvestBuyVMReviewTest : InvestBuyVMTestBase() {
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
    fun `a buy the engine recorded is followed, not offered again, even when the price lapsed meanwhile`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            // The prompt outlasts the held price, then the send broadcasts and fails uncertainly: the engine keeps
            // the checkpoint, so ZEC may have gone out.
            fixture.repo.onExecute = {
                delay(11.minutes.inWholeMilliseconds)
                fixture.repo.pendingTrades.value = listOf(PendingTrade("t1deposit", NVIDIA.assetId, isSale = false))
                throw IOException("broadcast, then no answer")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(11.minutes.inWholeMilliseconds + 100)
            runCurrent()

            assertNull(fixture.reviewState())
            verify { fixture.router.replace(InvestProgressArgs("t1deposit", NVIDIA.assetId, "100")) }
            assertEquals(1, fixture.repo.prepareCalls.size)
            assertFalse(fixture.vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `a trade of the stock appearing while the sheet is open closes it instead of paying`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }

            val review = fixture.openReview()
            fixture.repo.pendingTrades.value = listOf(PendingTrade("0xsale", NVIDIA.assetId, isSale = true))
            runCurrent()
            review.primaryButton.onClick()
            runCurrent()

            assertNull(fixture.reviewState())
            assertTrue(fixture.repo.executeCalls.isEmpty())
            assertNotNull(fixture.vm.state.value.tradeInProgress)
        }

    @Test
    fun `a failure while the trade records can't be read is never taken for nothing sent`() =
        runTest {
            val fixture = fixture()
            fixture.repo.onEstimate = { _, _ -> PRICED }
            fixture.repo.onPrepare = { asset, _ -> preparedBuy(asset, fixture.now() + 10.minutes) }
            fixture.repo.onExecute = {
                delay(11.minutes.inWholeMilliseconds)
                fixture.repo.pendingTrades.value = null
                throw IOException("broadcast, then no answer")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(11.minutes.inWholeMilliseconds + 100)
            runCurrent()

            // Not "Refresh price": whether ZEC went out is unknown, so the sheet closes and support is the way on.
            assertNull(fixture.reviewState())
            assertEquals(
                stringRes(R.string.invest_trades_unreadable),
                fixture.vm.state.value.tradeInProgress
                    ?.text,
            )
            assertFalse(fixture.vm.state.value.primaryButton.isEnabled)
            verify(exactly = 0) { fixture.router.replace(any()) }
        }

    @Test
    fun `the buy screen follows pending trades while it asks to`() =
        runTest {
            val fixture = fixture()

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.vm.followPendingTrades() }

            assertEquals(1, follower.followers)
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
    fun `a change of currency asks again for the same typed amount`() =
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

    /** The rate moves with every ZEC price tick; that alone neither re-quotes nor throws away the quote in flight. */
    @Test
    fun `a rate that drifts in the same currency keeps the estimate`() =
        runTest {
            val rates = MutableStateFlow(InvestCurrency("EUR", "€", BigDecimal("0.92")))
            val fixture = fixture(currency = { rates })
            val reply = CompletableDeferred<BuyEstimate>()
            fixture.repo.onEstimate = { _, _ -> reply.await() }

            fixture.type("100")
            advanceTimeBy(InvestBuyVM.AMOUNT_SETTLE_DELAY_MS + 1)
            runCurrent()
            rates.value = InvestCurrency("EUR", "€", BigDecimal("0.9201"))
            runCurrent()
            reply.complete(PRICED)
            advanceUntilIdle()

            assertEquals(1, fixture.repo.estimateCalls.size)
            assertTrue(fixture.vm.state.value.primaryButton.isEnabled)
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

    @Test
    fun `where buying isn't offered, Review stays off and the sell-only note links to Settings`() =
        runTest {
            val fixture = fixture(settings = INVEST_SELL_ONLY)
            fixture.repo.onEstimate = { _, _ -> PRICED }

            fixture.type("100")
            advanceUntilIdle()
            val state = fixture.vm.state.value
            assertNotNull(state.ledger)
            assertFalse(state.primaryButton.isEnabled)
            val note = assertNotNull(state.sellOnly)
            assertEquals(stringRes(R.string.invest_sell_only_banner, InvestFormat.countryName("CA")), note.text)

            state.primaryButton.onClick()
            advanceUntilIdle()
            assertTrue(fixture.repo.prepareCalls.isEmpty())
            note.onOpen()
            verify { fixture.router.forward(InvestSettingsArgs) }
        }

    @Test
    fun `where buying is offered there is no sell-only note`() =
        runTest {
            assertNull(
                fixture()
                    .vm.state.value.sellOnly
            )
        }
}
