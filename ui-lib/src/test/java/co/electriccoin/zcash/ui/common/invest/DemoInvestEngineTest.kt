package co.electriccoin.zcash.ui.common.invest

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.demo.DemoInvestEngine
import co.electriccoin.zcash.ui.common.invest.demo.DemoInvestSellRepository
import co.electriccoin.zcash.ui.common.invest.demo.DemoWallet
import co.electriccoin.zcash.ui.common.invest.demo.InvestDemoControls
import co.electriccoin.zcash.ui.common.invest.demo.InvestDemoOutcome
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.screen.invest.FakeInvestSettingsRepository
import co.electriccoin.zcash.ui.screen.invest.INVEST_READY
import co.electriccoin.zcash.ui.screen.invest.INVEST_SELL_ONLY
import co.electriccoin.zcash.ui.screen.invest.virtualClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DemoInvestEngineTest {
    @Test
    fun `a completed buy steps through every state and lands in the holdings`() =
        runTest {
            val demo = demo()

            val address = demo.buy()
            assertEquals(listOf(address), demo.engine.pendingBuys.first())
            val steps = demo.engine.observeBuy(address).toList()

            assertEquals(
                listOf(
                    BuyProgress.SendingZec::class,
                    BuyProgress.PaymentReceived::class,
                    BuyProgress.Buying::class,
                    BuyProgress.Held::class,
                ),
                steps.map { it::class },
            )
            assertTrue(
                demo.engine.pendingTrades
                    .first()!!
                    .isEmpty()
            )
            assertEquals(
                NVIDIA,
                demo.engine.holdings.value!!
                    .items
                    .single()
                    .asset
            )
        }

    @Test
    fun `a buy that needs attention stays pending until dismissed, and holds nothing`() =
        runTest {
            val demo = demo()
            demo.controls.setOutcome(InvestDemoOutcome.NEEDS_ATTENTION)

            val address = demo.buy()
            advanceUntilIdle()

            assertIs<BuyProgress.NeedsAttention>(
                demo.engine
                    .observeBuy(address)
                    .toList()
                    .last()
            )
            assertEquals(
                1,
                demo.engine.pendingTrades
                    .first()!!
                    .size
            )
            demo.engine.dismissBuy(address)
            assertTrue(
                demo.engine.pendingTrades
                    .first()!!
                    .isEmpty()
            )
            assertNull(demo.engine.holdings.value)
        }

    @Test
    fun `one trade per stock, and none while buying isn't offered`() =
        runTest {
            val demo = demo()
            demo.buy()
            assertFailsWith<IllegalStateException> { demo.engine.prepareBuy(NVIDIA, BigDecimal(100)) }

            val sellOnly = demo(settings = INVEST_SELL_ONLY)
            assertFailsWith<IllegalStateException> { sellOnly.engine.prepareBuy(NVIDIA, BigDecimal(100)) }
        }

    @Test
    fun `quotes keep the minimum, and no liquidity means no price`() =
        runTest {
            val demo = demo()

            assertIs<BuyEstimate.BelowMinimum>(demo.engine.estimateBuy(NVIDIA, BigDecimal(39)))
            val priced = assertIs<BuyEstimate.Priced>(demo.engine.estimateBuy(NVIDIA, BigDecimal(100)))
            assertEquals(BigDecimal("2.00000000"), priced.zecIn)

            demo.controls.setLiquidity(false)
            assertIs<BuyEstimate.NoPrice>(demo.engine.estimateBuy(NVIDIA, BigDecimal(100)))
            assertFailsWith<InvestApiException.NoPrice> { demo.engine.prepareBuy(NVIDIA, BigDecimal(100)) }
        }

    @Test
    fun `selling all of a holding sends ZEC and empties it, even in sell-only`() =
        runTest {
            val demo = demo()
            demo.engine.observeBuy(demo.buy()).toList()
            demo.settings.setResidence("CA", qualifiedInvestor = false)

            assertIs<SellEstimate.ExceedsHolding>(demo.sells.estimateSell(NVIDIA, SellAmount.Units(BigDecimal(5))))
            val prepared = demo.sells.prepareSell(NVIDIA, SellAmount.All)
            val address = demo.sells.executeSell(prepared)
            val steps = demo.sells.observeSell(address).toList()

            assertIs<SellProgress.Sent>(steps.last())
            assertTrue(
                demo.engine.holdings.value!!
                    .items
                    .isEmpty()
            )
            assertIs<SellEstimate.NothingHeld>(demo.sells.estimateSell(NVIDIA, SellAmount.All))
        }

    @Test
    fun `a sale that is refunded keeps the stock`() =
        runTest {
            val demo = demo()
            demo.engine.observeBuy(demo.buy()).toList()
            demo.controls.setOutcome(InvestDemoOutcome.REFUNDED)

            val address = demo.sells.executeSell(demo.sells.prepareSell(NVIDIA, SellAmount.All))

            assertIs<SellProgress.ReturnedToAccount>(
                demo.sells
                    .observeSell(address)
                    .toList()
                    .last()
            )
            assertEquals(
                1,
                demo.engine.holdings.value!!
                    .items.size
            )
        }

    @Test
    fun `reset clears holdings and trades`() =
        runTest {
            val demo = demo()
            demo.engine.observeBuy(demo.buy()).toList()

            demo.engine.reset()

            assertNull(demo.engine.holdings.value)
            assertTrue(
                demo.engine.pendingTrades
                    .first()!!
                    .isEmpty()
            )
            assertIs<SellEstimate.NothingHeld>(demo.sells.estimateSell(NVIDIA, SellAmount.All))
        }

    @Test
    fun `reset stops trades in flight, so a buy finishing later doesn't bring shares back`() =
        runTest {
            val demo = demo()
            val address = demo.buy()
            advanceTimeBy(DemoInvestEngine.STEP_MS + 1)

            demo.engine.reset()
            advanceUntilIdle()

            assertNull(demo.engine.holdings.value)
            assertNull(demo.engine.trades.value[address])
            assertIs<SellEstimate.NothingHeld>(demo.sells.estimateSell(NVIDIA, SellAmount.All))
        }

    @Test
    fun `the wallet starts at 9 ZEC, a buy spends its ZEC and fee, and a sale pays back in`() =
        runTest {
            val demo = demo()
            assertEquals(Zatoshi(900_000_000L), demo.wallet.balance.value)

            demo.engine.observeBuy(demo.buy()).toList()
            // $100 at $50 a ZEC is 2 ZEC, plus the 0.0001 network fee.
            assertEquals(Zatoshi(699_990_000L), demo.wallet.balance.value)

            val sale = demo.sells.prepareSell(NVIDIA, SellAmount.All)
            demo.sells.observeSell(demo.sells.executeSell(sale)).toList()
            assertEquals(699_990_000L + sale.zecOutExpected.movePointRight(8).toLong(), demo.wallet.balance.value.value)
        }

    @Test
    fun `a refunded buy returns its ZEC less the refund fee`() =
        runTest {
            val demo = demo()
            demo.controls.setOutcome(InvestDemoOutcome.REFUNDED)

            demo.engine.observeBuy(demo.buy()).toList()

            // 9 - 2.0001 + (2 - 0.00032)
            assertEquals(Zatoshi(899_958_000L), demo.wallet.balance.value)
        }

    @Test
    fun `more than the wallet holds says not enough ZEC, and reset refills it`() =
        runTest {
            val demo = demo()

            assertIs<BuyEstimate.InsufficientZec>(demo.engine.estimateBuy(NVIDIA, BigDecimal(1_000)))
            assertFailsWith<IllegalStateException> { demo.engine.prepareBuy(NVIDIA, BigDecimal(1_000)) }

            demo.engine.observeBuy(demo.buy()).toList()
            demo.engine.reset()
            assertEquals(DemoWallet.START, demo.wallet.balance.value)
        }

    @Test
    fun `two buys held at once can't spend the same ZEC`() =
        runTest {
            val demo = demo()
            val tesla = InvestAssets.curated.first { it.ticker == "TSLA" }
            // $300 at $50 a ZEC is 6 ZEC each: both fit 9 ZEC when prepared, only one when paid.
            val first = demo.engine.prepareBuy(NVIDIA, BigDecimal(300))
            val second = demo.engine.prepareBuy(tesla, BigDecimal(300))

            demo.engine.executeBuy(first)
            assertFailsWith<IllegalStateException> { demo.engine.executeBuy(second) }

            assertEquals(Zatoshi(299_990_000L), demo.wallet.balance.value)
            assertEquals(
                listOf(NVIDIA.assetId),
                demo.engine.pendingTrades
                    .first()!!
                    .map { it.assetId }
            )
        }

    @Test
    fun `a buy refused for a stock already trading gives its ZEC back`() =
        runTest {
            val demo = demo()
            val first = demo.engine.prepareBuy(NVIDIA, BigDecimal(100))
            val second = demo.engine.prepareBuy(NVIDIA, BigDecimal(100))

            demo.engine.executeBuy(first)
            assertFailsWith<IllegalStateException> { demo.engine.executeBuy(second) }

            assertEquals(Zatoshi(699_990_000L), demo.wallet.balance.value)
        }

    private class Demo(
        val engine: DemoInvestEngine,
        val sells: DemoInvestSellRepository,
        val controls: InvestDemoControls,
        val settings: FakeInvestSettingsRepository,
        val wallet: DemoWallet,
    ) {
        suspend fun buy(): String = engine.executeBuy(engine.prepareBuy(NVIDIA, BigDecimal(100)))
    }

    private fun TestScope.demo(settings: InvestSettings = INVEST_READY): Demo {
        val controls = InvestDemoControls()
        val wallet = DemoWallet()
        val settingsRepo = FakeInvestSettingsRepository(settings)
        val account =
            mockk<ZashiAccount>().also { every { it.spendableShieldedBalance } answers { wallet.balance.value } }
        val accounts = mockk<AccountDataSource>().also { coEvery { it.getSelectedAccount() } returns account }
        val zec = mockk<SwapAsset>(relaxed = true).also { every { it.usdPrice } returns BigDecimal(50) }
        val swap =
            mockk<SwapRepository>().also {
                every { it.assets } returns MutableStateFlow(SwapAssetsData(zecAsset = zec))
            }
        val clock = virtualClock()
        val engine = DemoInvestEngine(accounts, swap, settingsRepo, controls, wallet, clock, scope = backgroundScope)
        val sells = DemoInvestSellRepository(engine, controls, clock)
        return Demo(engine, sells, controls, settingsRepo, wallet)
    }

    private companion object {
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
    }
}
