package co.electriccoin.zcash.ui.screen.invest

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.MarketAsset
import co.electriccoin.zcash.ui.common.model.DynamicSimpleSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.receipt.InvestReceiptArgs
import co.electriccoin.zcash.ui.screen.invest.receipt.InvestReceiptState
import co.electriccoin.zcash.ui.screen.invest.receipt.InvestReceiptVM
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class InvestReceiptVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a held buy shows its value at today's price first, then the shares, fees and a support reference`() =
        runTest {
            val fixture = fixture(record(SwapStatus.SUCCESS), progress = BuyProgress.Held(DEPOSIT, null))

            val state = fixture.state()

            assertEquals(stringRes(R.string.invest_receipt_title_bought, "NVIDIA"), state.title)
            // 0.4410 × $224.46
            assertEquals(stringRes("$98.99"), state.value)
            assertEquals(stringRes("0.4410 NVDA"), state.units)
            assertEquals(stringRes(R.string.invest_progress_step_held), state.status)
            assertEquals(stringRes("$0.95"), state.fees)
            assertNull(state.progressButton)
            assertFalse(state.isSupportOpen)

            state.onToggleSupport()
            advanceUntilIdle()
            assertTrue(fixture.vm.state.value.isSupportOpen)
            assertEquals(DEPOSIT, fixture.vm.state.value.reference)
            fixture.vm.state.value
                .onCopyReference()
            verify { fixture.copy(DEPOSIT, false) }
        }

    @Test
    fun `without a live status the record's status stands, and a pending buy links to its progress`() =
        runTest {
            val fixture = fixture(record(SwapStatus.PENDING), progress = null)

            val state = fixture.state()

            assertEquals(stringRes(R.string.invest_progress_title, "NVIDIA"), state.title)
            assertNull(state.value)
            assertEquals(stringRes(R.string.invest_receipt_status_pending), state.status)
            assertNotNull(state.progressButton).onClick()
            verify { fixture.router.forward(InvestProgressArgs(depositAddress = DEPOSIT)) }
        }

    @Test
    fun `a refunded buy shows no value and says so`() =
        runTest {
            val state = fixture(record(SwapStatus.REFUNDED), progress = null).state()

            assertEquals(stringRes(R.string.invest_progress_refunded_title), state.title)
            assertNull(state.value)
            assertNull(state.units)
        }

    private inner class Fixture(
        val vm: InvestReceiptVM,
        val router: NavigationRouter,
        val copy: CopyToClipboardUseCase,
        private val scope: TestScope,
    ) {
        fun state(): InvestReceiptState {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.advanceUntilIdle()
            return vm.state.value
        }
    }

    private fun TestScope.fixture(
        record: TransactionSwapMetadata?,
        progress: BuyProgress?,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        repo.market.value =
            InvestMarket(
                assets = listOf(MarketAsset(NVIDIA, BigDecimal("224.46"))),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(START_MILLIS),
            )
        repo.onObserve = { if (progress == null) flowOf() else flowOf(progress) }
        val metadata = mockk<MetadataRepository>().also { coEvery { it.getSwapMetadata(DEPOSIT) } returns record }
        val router = mockk<NavigationRouter>(relaxed = true)
        val copy = mockk<CopyToClipboardUseCase>(relaxed = true)
        val vm = InvestReceiptVM(InvestReceiptArgs(DEPOSIT), repo, metadata, copy, router)
        return Fixture(vm, router, copy, this)
    }

    private fun record(status: SwapStatus) =
        TransactionSwapMetadata(
            depositAddress = DEPOSIT,
            provider = "near",
            totalFees = Zatoshi(0),
            totalFeesUsd = BigDecimal("0.95"),
            lastUpdated = java.time.Instant.ofEpochMilli(START_MILLIS),
            origin = mockk(relaxed = true),
            destination =
                DynamicSimpleSwapAsset("NVDAon", stringRes("NVIDIA"), mockk(relaxed = true), mockk(relaxed = true)),
            mode = SwapMode.EXACT_INPUT,
            status = status,
            amountOutFormatted = BigDecimal("0.4410"),
        )

    private companion object {
        const val DEPOSIT = "t1deposit"
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
    }
}
