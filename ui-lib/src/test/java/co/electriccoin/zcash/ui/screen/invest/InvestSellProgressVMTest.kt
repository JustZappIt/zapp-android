package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressVM
import co.electriccoin.zcash.ui.screen.invest.sellprogress.SellProgressToSteps
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvestSellProgressVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `each status maps to the step list the UX plan draws`() {
        fun statuses(progress: SellProgress) = SellProgressToSteps.steps(progress, NVIDIA).map { it.status }

        assertEquals(
            listOf(ZappStepStatus.InProgress, ZappStepStatus.Pending, ZappStepStatus.Pending),
            statuses(SellProgress.Authorised(DEPOSIT)),
        )
        assertEquals(
            listOf(ZappStepStatus.Completed, ZappStepStatus.InProgress, ZappStepStatus.Pending),
            statuses(SellProgress.Selling(DEPOSIT)),
        )
        assertEquals(List(3) { ZappStepStatus.Completed }, statuses(SellProgress.Sent(DEPOSIT, BigDecimal.ONE)))
        assertEquals(List(2) { ZappStepStatus.Completed }, statuses(SellProgress.ReturnedToAccount(DEPOSIT)))
        assertEquals(
            listOf(ZappStepStatus.Completed, ZappStepStatus.Failed),
            statuses(SellProgress.NotSold(DEPOSIT)),
        )
        assertEquals(
            listOf(ZappStepStatus.Completed, ZappStepStatus.Failed),
            statuses(SellProgress.NeedsAttention(DEPOSIT, "hash")),
        )
    }

    @Test
    fun `a sale that went through shows the success header with the ZEC`() =
        runTest {
            val fixture = fixture(flowOf(SellProgress.Sent(DEPOSIT, BigDecimal("0.0634"))))

            val state = fixture.state()

            assertTrue(state.isSuccess)
            assertEquals(stringRes(R.string.invest_sell_sent_title), state.title)
            assertEquals(stringRes(R.string.invest_sell_sent_subtitle, "0.0634 ZEC"), state.subtitle)
        }

    @Test
    fun `a sale that didn't run says the stock never left`() =
        runTest {
            val state = fixture(flowOf(SellProgress.NotSold(DEPOSIT))).state()

            assertFalse(state.isSuccess)
            assertEquals(stringRes(R.string.invest_sell_not_sold_title), state.title)
            assertEquals(stringRes(R.string.invest_sell_not_sold_subtitle, "NVIDIA"), state.subtitle)
        }

    @Test
    fun `a sale that needs attention shows its reference, contacts support and can be dismissed`() =
        runTest {
            val fixture = fixture(flowOf(SellProgress.NeedsAttention(DEPOSIT, "intenthash")))

            val state = fixture.state()
            assertEquals(stringRes(R.string.invest_progress_attention_body, "intenthash"), state.attention)

            assertNotNull(state.contactSupportButton).onClick()
            verify {
                fixture.router.forward(
                    SupportChatArgs(prefilledMessage = "My Invest sell needs attention. Reference: intenthash"),
                )
            }

            assertNotNull(state.removeButton).onClick()
            advanceUntilIdle()
            assertEquals(listOf(DEPOSIT), fixture.sell.dismissed)
            verify { fixture.router.back() }
        }

    @Test
    fun `while the sale runs the amount shows in the user's currency and nothing can be removed`() =
        runTest {
            val state = fixture(flowOf(SellProgress.Selling(DEPOSIT)), usdAmount = "98.99").state()

            assertEquals(stringRes(R.string.invest_sell_progress_title, "NVIDIA"), state.title)
            assertEquals(stringRes(R.string.invest_progress_subtitle, "$98.99"), state.subtitle)
            assertNull(state.removeButton)
            assertNull(state.contactSupportButton)
        }

    private inner class Fixture(
        val vm: InvestSellProgressVM,
        val repo: FakeInvestRepository,
        val sell: FakeInvestSellRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        fun state() =
            vm.state.value.let {
                scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
                scope.advanceUntilIdle()
                vm.state.value
            }
    }

    private fun TestScope.fixture(
        updates: Flow<SellProgress>,
        usdAmount: String? = null,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        val sell = FakeInvestSellRepository().also { it.onObserve = { updates } }
        val router = mockk<NavigationRouter>(relaxed = true)
        val vm =
            InvestSellProgressVM(
                InvestSellProgressArgs(DEPOSIT, NVIDIA.assetId, usdAmount),
                sell,
                USD_CURRENCY,
                router,
            )
        return Fixture(vm, repo, sell, router, this)
    }

    private companion object {
        const val DEPOSIT = "0xdeposit"
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
    }
}
