package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.MarketAsset
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.common.InvestSession
import co.electriccoin.zcash.ui.screen.invest.common.UsMarketHours
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeState
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeVM
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MaxLineLength")
class InvestHomeVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `with Tor off the banner shows, never blocks, and can be dismissed or open the Tor setting`() =
        runTest {
            val fixture = fixture(torOn = false)

            val banner = assertNotNull(fixture.state().torBanner)
            banner.onTurnOn()
            verify { fixture.router.forward(TorSettingsArgs) }

            banner.onDismiss()
            advanceUntilIdle()
            assertNull(fixture.vm.state.value.torBanner)
            assertEquals(2, fixture.vm.state.value.groups.size)

            // Remembered for the app session: opening Invest home again doesn't bring it back.
            assertNull(fixture(torOn = false).state().torBanner)
        }

    @Test
    fun `with Tor on there is no banner`() =
        runTest {
            assertNull(fixture(torOn = true).state().torBanner)
        }

    @Test
    fun `curated stocks come in two groups, and a Saturday closes the weekday group and shows the market banner`() =
        runTest {
            val fixture = fixture(nowMillis = SATURDAY_MILLIS)
            fixture.repo.market.value = market(nvdaPrice = BigDecimal("224.46"))

            val state = fixture.state()

            assertEquals(listOf(6, 4), state.groups.map { it.rows.size })
            assertNull(state.groups[0].status)
            assertEquals(stringRes(R.string.invest_home_closed_chip), state.groups[1].status)
            assertEquals(R.string.invest_home_market_banner, (state.marketBanner as StringResource.ByResource).resource)
            val nvda = state.groups[0].rows.first()
            assertEquals("NVIDIA", nvda.name)
            assertEquals("NVDA", nvda.ticker)
            assertEquals("NV", nvda.monogram)
            assertEquals(stringRes("$224.46"), nvda.price)
            assertEquals(stringRes(R.string.invest_home_per_share), nvda.caption)
        }

    @Test
    fun `during US regular hours there is no market banner`() =
        runTest {
            assertTrue(UsMarketHours.isRegularSession(Instant.ofEpochMilli(START_MILLIS)))
            assertNull(fixture(nowMillis = START_MILLIS).state().marketBanner)
        }

    @Test
    fun `a stock that loses its price keeps showing the last one, marked as such`() =
        runTest {
            val fixture = fixture()
            fixture.repo.market.value = market(nvdaPrice = BigDecimal("224.46"))
            fixture.state()

            fixture.repo.market.value = market(nvdaPrice = null)
            advanceUntilIdle()

            val nvda =
                fixture.vm.state.value.groups[0]
                    .rows
                    .first()
            assertEquals(stringRes("$224.46"), nvda.price)
            assertEquals(stringRes(R.string.invest_home_last_price), nvda.caption)
            assertFalse(nvda.isPriced)
            val tesla =
                fixture.vm.state.value.groups[0]
                    .rows[1]
            assertNull(tesla.price)
            assertEquals(stringRes(R.string.invest_no_price_short), tesla.caption)
        }

    @Test
    fun `pending trades are named by stock and lead to their progress, and a stock opens the buy screen`() =
        runTest {
            val fixture = fixture()
            fixture.repo.pendingTrades.value =
                listOf(
                    PendingTrade("t1pending", NVIDIA.assetId, isSale = false),
                    PendingTrade("0xsale", TESLA.assetId, isSale = true),
                )

            val state = fixture.state()
            assertEquals(
                listOf(
                    stringRes(R.string.invest_home_pending_buy_title, "NVIDIA"),
                    stringRes(R.string.invest_home_pending_sale_title, "Tesla"),
                ),
                state.pendingTrades.map { it.title },
            )
            state.pendingTrades.forEach { it.onClick() }
            state.groups[0]
                .rows
                .first()
                .onClick()

            verify { fixture.router.forward(InvestProgressArgs(depositAddress = "t1pending", assetId = NVIDIA.assetId)) }
            verify { fixture.router.forward(InvestSellProgressArgs(depositAddress = "0xsale", assetId = TESLA.assetId)) }
            verify { fixture.router.forward(InvestBuyArgs(NVIDIA.assetId)) }
        }

    @Test
    fun `unreadable trade records stop every Sell and point to support`() =
        runTest {
            val fixture = fixture()
            fixture.repo.holdings.value = HOLDINGS
            fixture.repo.pendingTrades.value = null

            val state = fixture.state()

            assertEquals(stringRes(R.string.invest_trades_unreadable), assertNotNull(state.recordsUnreadable).text)
            assertNull(
                state.summary!!
                    .rows
                    .single()
                    .onSell,
            )
            assertTrue(state.pendingTrades.isEmpty())
        }

    @Test
    fun `Invest home follows pending trades while it is open`() =
        runTest {
            val fixture = fixture()
            fixture.state()

            assertEquals(1, follower.followers)
        }

    @Test
    fun `a holding offers Sell, and while a sale of it runs it says so and links to the sale`() =
        runTest {
            val fixture = fixture()
            fixture.repo.holdings.value = HOLDINGS

            val row =
                fixture
                    .state()
                    .summary!!
                    .rows
                    .single()
            assertNull(row.tradeInProgress)
            assertNotNull(row.onSell).invoke()
            verify { fixture.router.forward(InvestSellArgs(NVIDIA.assetId)) }

            fixture.repo.pendingTrades.value = listOf(PendingTrade("0xsale", NVIDIA.assetId, isSale = true))
            advanceUntilIdle()

            val state = fixture.vm.state.value
            val selling = state.summary!!.rows.single()
            assertTrue(assertNotNull(selling.tradeInProgress).isSale)
            assertNull(selling.onSell)
            state.pendingTrades.single().onClick()
            selling.tradeInProgress.onOpen()
            verify(exactly = 2) { fixture.router.forward(InvestSellProgressArgs("0xsale", NVIDIA.assetId)) }
        }

    private inner class Fixture(
        val vm: InvestHomeVM,
        val repo: FakeInvestRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        fun state(): InvestHomeState {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.advanceUntilIdle()
            return vm.state.value
        }
    }

    private val follower = FakeInvestTradeFollower()

    // One session per test, as the app has one per run.
    private val session = InvestSession()

    private fun TestScope.fixture(
        torOn: Boolean = true,
        nowMillis: Long = START_MILLIS,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        val router = mockk<NavigationRouter>(relaxed = true)
        val tor = mockk<IsTorEnabledUseCase>().also { every { it.observe() } returns MutableStateFlow(torOn) }
        val clock = virtualClock(kotlin.time.Instant.fromEpochMilliseconds(nowMillis))
        val vm = InvestHomeVM(repo, tor, USD_CURRENCY, follower, session, router, clock)
        return Fixture(vm, repo, router, this)
    }

    private fun market(nvdaPrice: BigDecimal?) =
        InvestMarket(
            assets =
                InvestAssets.curated.map { asset ->
                    MarketAsset(asset, if (asset == NVIDIA) nvdaPrice else null)
                },
            updatedAt = kotlin.time.Instant.fromEpochMilliseconds(START_MILLIS),
        )

    private companion object {
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
        val TESLA = InvestAssets.curated.first { it.ticker == "TSLA" }
        val HOLDINGS =
            Holdings(
                items = listOf(Holding(NVIDIA, BigDecimal("0.4410"), BigDecimal("98.99"))),
                totalUsd = BigDecimal("98.99"),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(START_MILLIS),
                isStale = false,
            )
    }
}
