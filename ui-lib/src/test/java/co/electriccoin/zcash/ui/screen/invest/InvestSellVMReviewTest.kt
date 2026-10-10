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
import kotlinx.coroutines.CompletableDeferred
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
internal class InvestSellVMReviewTest : InvestSellVMTestBase() {
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
            // The ZEC floor, and roughly what it is worth in the user's currency (not a guaranteed floor).
            assertEquals(stringRes(R.string.invest_sell_zec_at_least_with_value, "0.0628 ZEC", "$94.20"), review.atLeast)
            assertEquals(stringRes(R.string.invest_sell_zec_with_value, "0.0634 ZEC", "$95.10"), review.expected)
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

    @Test
    fun `a new exchange rate re-asks rather than leaving the quote on getting a price`() =
        runTest {
            val rates = MutableStateFlow(InvestCurrency.USD)
            val fixture = fixture(currency = { rates })
            fixture.sell.onEstimate = { _, _ -> PRICED }

            fixture.type("50")
            advanceTimeBy(InvestSellVM.AMOUNT_SETTLE_DELAY_MS - 100)
            rates.value = InvestCurrency("EUR", "€", BigDecimal("0.92"))
            advanceUntilIdle()

            assertEquals(listOf<SellAmount>(SellAmount.Usd(BigDecimal("54.34"))), fixture.sell.estimateCalls)
            assertNotNull(
                fixture.vm.state.value.ledger
                    ?.youSell
            )
            assertNull(fixture.vm.state.value.notice)
        }

    /** The rate moves with every ZEC price tick; that alone neither re-quotes nor throws away the quote in flight. */
    @Test
    fun `a rate that drifts in the same currency keeps the estimate`() =
        runTest {
            val rates = MutableStateFlow(InvestCurrency("EUR", "€", BigDecimal("0.92")))
            val fixture = fixture(currency = { rates })
            val reply = CompletableDeferred<SellEstimate>()
            fixture.sell.onEstimate = { _, _ -> reply.await() }

            fixture.type("50")
            advanceTimeBy(InvestSellVM.AMOUNT_SETTLE_DELAY_MS + 1)
            runCurrent()
            rates.value = InvestCurrency("EUR", "€", BigDecimal("0.9201"))
            runCurrent()
            reply.complete(PRICED)
            advanceUntilIdle()

            assertEquals(1, fixture.sell.estimateCalls.size)
            assertNotNull(
                fixture.vm.state.value.ledger
                    ?.youSell
            )
        }

    @Test
    fun `tapping Review and Confirm twice prepares and signs once`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ ->
                delay(1_000)
                preparedSell(asset, fixture.now() + 10.minutes)
            }
            fixture.sell.onExecute = {
                delay(1_000)
                "0xdeposit"
            }

            fixture.type("98.99")
            advanceTimeBy(InvestSellVM.AMOUNT_SETTLE_DELAY_MS + 1)
            runCurrent()
            val review = fixture.vm.state.value.primaryButton
            review.onClick()
            review.onClick()
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(1, fixture.sell.prepareCalls.size)

            val confirm = fixture.reviewState()!!.primaryButton
            confirm.onClick()
            confirm.onClick()
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(1, fixture.sell.executeCalls.size)
        }

    @Test
    fun `an uncertain failure for a sale the engine now lists opens its progress`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = {
                fixture.repo.pendingTrades.value = listOf(PendingTrade("0xdeposit", NVIDIA.assetId, isSale = true))
                throw IOException("submit went quiet")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            verify { fixture.router.replace(InvestSellProgressArgs("0xdeposit", NVIDIA.assetId, "98.99")) }
        }

    @Test
    fun `an uncertain failure with nothing recorded closes the sheet and says it didn't complete`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = { throw IOException("no route") }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            assertEquals(stringRes(R.string.invest_sell_review_failed), fixture.vm.state.value.notice)
        }

    @Test
    fun `a refusal because another trade of the stock is pending keeps the sheet with Confirm off`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = {
                fixture.repo.pendingTrades.value = listOf(PendingTrade("t1buy", NVIDIA.assetId, isSale = false))
                error("A buy or sale of this stock is still in progress")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            val review = assertNotNull(fixture.reviewState())
            assertEquals(stringRes(R.string.invest_trade_in_flight, "NVIDIA"), review.errorText)
            assertFalse(review.primaryButton.isEnabled)
        }

    @Test
    fun `a price that lapses during the biometric prompt asks for a fresh one`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = {
                delay(11.minutes.inWholeMilliseconds)
                error("The price is no longer held; prepare the sale again")
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
    fun `a sale the engine recorded is followed, not offered again, even when the price lapsed meanwhile`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = {
                delay(11.minutes.inWholeMilliseconds)
                fixture.repo.pendingTrades.value = listOf(PendingTrade("0xdeposit", NVIDIA.assetId, isSale = true))
                throw IOException("submitted, then no answer")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(11.minutes.inWholeMilliseconds + 100)
            runCurrent()

            assertNull(fixture.reviewState())
            verify { fixture.router.replace(InvestSellProgressArgs("0xdeposit", NVIDIA.assetId, "98.99")) }
            assertEquals(1, fixture.sell.prepareCalls.size)
        }

    @Test
    fun `a failure while the trade records can't be read is never taken for nothing sent`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }
            fixture.sell.onExecute = {
                fixture.repo.pendingTrades.value = null
                throw IOException("submitted, then no answer")
            }

            fixture.openReview().primaryButton.onClick()
            advanceTimeBy(100)
            runCurrent()

            assertNull(fixture.reviewState())
            assertEquals(
                stringRes(R.string.invest_trades_unreadable),
                fixture.vm.state.value.tradeInProgress
                    ?.text,
            )
            assertFalse(fixture.vm.state.value.isRefused)
        }

    @Test
    fun `Confirm checks the clock, not the last countdown tick`() =
        runTest {
            val clock = SkewedClock(virtualClock())
            val fixture = fixture(clock = clock)
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.sell.onPrepare = { asset, _ -> preparedSell(asset, fixture.now() + 10.minutes) }

            val review = fixture.openReview()
            clock.skew = 11.minutes
            review.primaryButton.onClick()
            runCurrent()

            assertTrue(fixture.sell.executeCalls.isEmpty())
            assertTrue(fixture.reviewState()!!.isExpired)
        }

    @Test
    fun `when the trade records can't be read, Review is off and support is the way out`() =
        runTest {
            val fixture = fixture()
            fixture.sell.onEstimate = { _, _ -> PRICED }
            fixture.repo.pendingTrades.value = null

            fixture.type("98.99")
            advanceUntilIdle()

            val blocked = assertNotNull(fixture.vm.state.value.tradeInProgress)
            assertEquals(stringRes(R.string.invest_trades_unreadable), blocked.text)
            assertFalse(fixture.vm.state.value.primaryButton.isEnabled)
        }
}
