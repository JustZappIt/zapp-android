// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.account.OfframpSmartAccount
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.liveness.LivenessConfig
import xyz.justzappit.offramp.liveness.LivenessReader
import xyz.justzappit.offramp.liveness.LivenessStanding
import xyz.justzappit.offramp.liveness.LivenessStatus
import xyz.justzappit.offramp.liveness.LivenessVerificationDriver
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reclaim.ReclaimVerificationDriver
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.RpPerUsdcLimit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The widget reports back only by redirecting the browser to Zapp. A user who switches back by
 * hand instead leaves that redirect blocked behind them, and the run it was for would otherwise
 * wait on the selfie step for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncreaseReputationVMTest {
    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val accountProvider = mockk<SmartOfframpAccountProvider>()
    private val reputationReader = mockk<ReputationReader>()
    private val reclaimDriver = mockk<ReclaimVerificationDriver>()
    private val livenessReader = mockk<LivenessReader>()
    private val livenessDriver = mockk<LivenessVerificationDriver>()
    private val returns = LivenessReturnInbox()

    /** What the driver does once the user is away in the widget: wait, or move on by itself. */
    private var afterVerifying: suspend () -> LivenessStatus? = { awaitCancellation() }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `coming back without the redirect calls the selfie step off`() =
        runTest {
            val vm = viewModelAwayInTheWidget()

            vm.onScreenVisible()
            advanceTimeBy(GRACE_MILLIS + 1)
            runCurrent()

            val run = assertNotNull(vm.state.value.run)
            assertEquals(VerificationStage.FAILED, run.stage)
            assertEquals(stringRes(R.string.increase_reputation_liveness_error_no_return), run.error)
        }

    @Test
    fun `a run that moved on before the grace ran out is left alone`() =
        runTest {
            afterVerifying = {
                delay(REDIRECT_MILLIS)
                LivenessStatus.Submitting
            }
            val vm = viewModelAwayInTheWidget()

            vm.onScreenVisible()
            advanceTimeBy(GRACE_MILLIS + 1)
            runCurrent()

            assertEquals(VerificationStage.SUBMITTING, stageOf(vm))
        }

    @Test
    fun `cancelling the run drops the pending verdict with it`() =
        runTest {
            val vm = viewModelAwayInTheWidget()

            vm.onScreenVisible()
            assertNotNull(vm.state.value.secondaryAction).onClick()
            advanceTimeBy(GRACE_MILLIS + 1)
            runCurrent()

            assertNull(vm.state.value.run)
        }

    /** A screen whose selfie row has been tapped and whose widget the user has opened. */
    private fun TestScope.viewModelAwayInTheWidget(): IncreaseReputationVM {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { accountProvider.resolve() } returns OfframpSmartAccount(mockk<EvmKey>(), Address.parse(WALLET))
        coEvery { reputationReader.read(any(), any()) } returns summary()
        coEvery { livenessReader.read(any()) } returns
            LivenessStanding(isVerified = false, limit = Usdc6.ofMicros(0), tierCap = Usdc6.ofMicros(TIER_CAP))
        every { livenessDriver.verify(any(), any(), any()) } returns
            flow {
                emit(LivenessStatus.Preparing)
                emit(LivenessStatus.Ready(WIDGET_URL, expiresInSeconds = SESSION_SECONDS))
                emit(LivenessStatus.Verifying)
                afterVerifying()?.let { emit(it) }
                awaitCancellation()
            }

        val vm =
            IncreaseReputationVM(
                args = IncreaseReputationArgs(currency = CurrencyCode.Inr),
                navigationRouter = navigationRouter,
                accountProvider = accountProvider,
                reputationReader = reputationReader,
                verificationDriver = reclaimDriver,
                livenessConfig = LivenessConfig(API_URL, API_KEY, TENANT),
                livenessReader = livenessReader,
                livenessDriver = livenessDriver,
                livenessReturns = returns,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()

        assertNotNull(vm.state.value.liveness).onClick()
        runCurrent()
        assertEquals(VerificationStage.READY, stageOf(vm))
        // The view opens the browser, then tells the screen it did.
        assertNotNull(vm.state.value.primaryAction).onClick()
        runCurrent()
        assertEquals(VerificationStage.VERIFYING, stageOf(vm))
        return vm
    }

    private fun stageOf(vm: IncreaseReputationVM): VerificationStage? {
        val run = vm.state.value.run
        return run?.stage
    }

    private fun summary() =
        ReputationSummary(
            currency = CurrencyCode.Inr,
            points = bigIntegerValueOf(0),
            isBlacklisted = false,
            verified = emptySet(),
            awards = emptyMap(),
            buyLimit = Usdc6.ofMicros(0),
            maxBuyLimit = Usdc6.ofMicros(MAX_BUY),
            rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(1), bigIntegerValueOf(1)),
        )

    private companion object {
        const val WALLET = "0x111111111111111111111111111111111111baaf"
        const val API_URL = "https://liveness.example"
        const val API_KEY = "tenant-key"
        const val TENANT = "zapp"
        const val WIDGET_URL = "https://liveness.example/embed?handoff=handoff"
        const val SESSION_SECONDS = 900
        const val TIER_CAP = 20_000_000L
        const val MAX_BUY = 500_000_000L

        /** [IncreaseReputationVM.RETURN_GRACE_MILLIS]. */
        const val GRACE_MILLIS = 3_000L
        const val REDIRECT_MILLIS = 500L
    }
}
