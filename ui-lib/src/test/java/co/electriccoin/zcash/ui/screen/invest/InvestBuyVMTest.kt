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
internal class InvestBuyVMTest : InvestBuyVMTestBase() {
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
}
