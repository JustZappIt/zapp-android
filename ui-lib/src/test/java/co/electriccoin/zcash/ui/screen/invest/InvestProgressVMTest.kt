package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.progress.BuyProgressToSteps
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressVM
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvestProgressVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `each status maps to the step list the UX plan draws`() {
        fun statuses(progress: BuyProgress) = BuyProgressToSteps.steps(progress, NVIDIA).map { it.status }

        assertEquals(
            listOf(ZappStepStatus.InProgress, ZappStepStatus.Pending, ZappStepStatus.Pending, ZappStepStatus.Pending),
            statuses(BuyProgress.SendingZec(DEPOSIT)),
        )
        assertEquals(
            listOf(ZappStepStatus.Completed, ZappStepStatus.InProgress, ZappStepStatus.Pending, ZappStepStatus.Pending),
            statuses(BuyProgress.PaymentReceived(DEPOSIT, incomplete = false)),
        )
        assertEquals(
            listOf(
                ZappStepStatus.Completed,
                ZappStepStatus.Completed,
                ZappStepStatus.InProgress,
                ZappStepStatus.Pending,
            ),
            statuses(BuyProgress.Buying(DEPOSIT)),
        )
        assertEquals(List(4) { ZappStepStatus.Completed }, statuses(BuyProgress.Held(DEPOSIT, BigDecimal.ONE)))
        assertEquals(List(3) { ZappStepStatus.Completed }, statuses(BuyProgress.Refunded(DEPOSIT, BigDecimal.ONE)))
        assertEquals(
            listOf(ZappStepStatus.Completed, ZappStepStatus.Completed, ZappStepStatus.Failed),
            statuses(BuyProgress.NeedsAttention(DEPOSIT, "c8f4806d")),
        )
        assertEquals(listOf(ZappStepStatus.Failed), statuses(BuyProgress.Expired(DEPOSIT)))
    }

    @Test
    fun `an expired buy says nothing was bought`() {
        val expired = BuyProgress.Expired(DEPOSIT)

        assertEquals(stringRes(R.string.invest_progress_expired_title), BuyProgressToSteps.title(expired, NVIDIA))
        assertEquals(
            stringRes(R.string.invest_progress_expired_subtitle),
            BuyProgressToSteps.subtitle(expired, NVIDIA, "$100.00"),
        )
    }

    @Test
    fun `an incomplete deposit is a warning on payment received, not a failure`() {
        val steps = BuyProgressToSteps.steps(BuyProgress.PaymentReceived(DEPOSIT, incomplete = true), NVIDIA)

        assertEquals(ZappStepStatus.InProgress, steps[1].status)
        assertEquals(listOf(stringRes(R.string.invest_progress_step_received_incomplete)), steps[1].detailLines)
        assertTrue(steps.none { it.status == ZappStepStatus.Failed })
    }

    @Test
    fun `a resumed buy without its asset is worded generically`() {
        assertEquals(
            stringRes(R.string.invest_progress_title_unknown),
            BuyProgressToSteps.title(BuyProgress.Buying(DEPOSIT), asset = null),
        )
        assertEquals(
            stringRes(R.string.invest_progress_step_buying_unknown),
            BuyProgressToSteps.steps(BuyProgress.Buying(DEPOSIT), asset = null)[2].label,
        )
    }

    @Test
    fun `held shows the success header with the shares and refreshes holdings`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = FakeInvestRepository()
            val updates = MutableSharedFlow<BuyProgress>()
            repo.onObserve = { updates }
            val vm = InvestProgressVM(ARGS, repo, mockk(relaxed = true))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(stringRes(R.string.invest_progress_title, "NVIDIA"), vm.state.value.title)
            assertEquals(stringRes(R.string.invest_progress_subtitle, "$100.00"), vm.state.value.subtitle)
            assertFalse(vm.state.value.isSuccess)

            updates.emit(BuyProgress.Held(DEPOSIT, BigDecimal("0.4410")))
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state.isSuccess)
            assertEquals(stringRes(R.string.invest_progress_held_title), state.title)
            assertEquals(stringRes(R.string.invest_progress_held_subtitle, "0.4410 NVDA"), state.subtitle)
            assertEquals(1, repo.refreshHoldingsCalls)
        }

    @Test
    fun `needs attention shows the reference for support`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = FakeInvestRepository()
            repo.onObserve = { flowOf(BuyProgress.NeedsAttention(DEPOSIT, "c8f4806d")) }
            val vm = InvestProgressVM(ARGS, repo, mockk(relaxed = true))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(stringRes(R.string.invest_progress_attention_body, "c8f4806d"), vm.state.value.attention)
            assertEquals(stringRes(R.string.invest_progress_attention_title), vm.state.value.title)
        }

    @Test
    fun `a failed status check can be retried and Back to Pay returns to the tabs`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = FakeInvestRepository()
            repo.onObserve = { flow { throw InvestApiException.Unreachable(IOException()) } }
            val router = mockk<NavigationRouter>(relaxed = true)
            val vm = InvestProgressVM(ARGS, repo, router)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            val failed = vm.state.value
            assertEquals(stringRes(R.string.invest_error_unreachable), failed.checkError)

            repo.onObserve = { flowOf(BuyProgress.Buying(DEPOSIT)) }
            failed.onCheckAgain()
            advanceUntilIdle()
            assertNull(vm.state.value.checkError)
            assertEquals(
                ZappStepStatus.InProgress,
                vm.state.value.steps[2]
                    .status
            )

            assertNotNull(vm.state.value.primaryButton)
                .onClick()
            verify { router.backToRoot() }
        }

    @Test
    fun `a buy that needs attention can be removed from the list once its reference is shown`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = FakeInvestRepository()
            repo.onObserve = { flowOf(BuyProgress.NeedsAttention(DEPOSIT, "c8f4806d")) }
            val router = mockk<NavigationRouter>(relaxed = true)
            val vm = InvestProgressVM(ARGS, repo, router)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertNotNull(vm.state.value.removeButton)
                .onClick()
            advanceUntilIdle()

            assertEquals(listOf(DEPOSIT), repo.dismissedBuys)
            verify { router.back() }
        }

    @Test
    fun `only a buy that needs attention offers removal`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repo = FakeInvestRepository()
            repo.onObserve = { flowOf(BuyProgress.Buying(DEPOSIT)) }
            val vm = InvestProgressVM(ARGS, repo, mockk(relaxed = true))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertNull(vm.state.value.removeButton)
        }

    private companion object {
        const val DEPOSIT = "t1deposit"
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
        val ARGS = InvestProgressArgs(DEPOSIT, NVIDIA.assetId, "100")
    }
}
