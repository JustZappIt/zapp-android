package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.MarketAsset
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.common.UsMarketHours
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeState
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeVM
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
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
    fun `a pending buy offers a way back to its progress, and a stock opens the buy screen`() =
        runTest {
            val fixture = fixture()
            fixture.repo.pendingBuys.value = listOf("t1pending")

            val state = fixture.state()
            state.pendingBuys.single().onClick()
            state.groups[0]
                .rows
                .first()
                .onClick()

            verify { fixture.router.forward(InvestProgressArgs(depositAddress = "t1pending")) }
            verify { fixture.router.forward(InvestBuyArgs(NVIDIA.assetId)) }
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

    private fun TestScope.fixture(
        torOn: Boolean = true,
        nowMillis: Long = START_MILLIS,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        val router = mockk<NavigationRouter>(relaxed = true)
        val tor = mockk<IsTorEnabledUseCase>().also { every { it.observe() } returns MutableStateFlow(torOn) }
        val clock = virtualClock(kotlin.time.Instant.fromEpochMilliseconds(nowMillis))
        val vm = InvestHomeVM(repo, tor, USD_CURRENCY, router, clock)
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
    }
}
