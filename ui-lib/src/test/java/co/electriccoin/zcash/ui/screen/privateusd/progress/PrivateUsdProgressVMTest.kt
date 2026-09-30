// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.progress

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapProblem
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseArgs
import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import xyz.justzappit.offramp.atomicswap.SwapDeposit
import xyz.justzappit.offramp.atomicswap.ZcashTxId
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdProgressVMTest {
    private val now = Clock.System.now().epochSeconds
    private val swap = MutableStateFlow(AtomicSwapState())
    private val history = MutableStateFlow(listOf(record()))
    private val swaps =
        mockk<AtomicSwapRepository>(relaxed = true) {
            every { deployment } returns AtomicSwapTestnet.deployment
            every { state } returns swap
            every { history } returns this@PrivateUsdProgressVMTest.history
            coEvery { isUnderWay() } returns false
        }
    private val reverse = mockk<ReverseSwapRepository> { coEvery { isUnderWay() } returns false }
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private lateinit var vm: PrivateUsdProgressVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a step that keeps failing says why, and can be tried again at once`() =
        runTest {
            swap.value = confirming(problem = AtomicSwapProblem.RELAYER_UNREACHABLE)
            start()

            val problem = assertNotNull(vm.state.value.problem)
            assertEquals(stringRes(R.string.convert_problem_relayer), problem.message)
            checkNotNull(problem.onRetry).invoke()
            verify { swaps.retryNow() }
        }

    @Test
    fun `the claim-by-itself note shows only while its time is ahead and nothing's wrong`() =
        runTest {
            swap.value = confirming(t0 = now + 600)
            start()

            assertEquals(R.string.convert_maker_silent, (vm.state.value.note as StringResource.ByResource).resource)

            swap.value = confirming(t0 = now + 600, problem = AtomicSwapProblem.RELAYER_UNREACHABLE)
            assertNull(vm.state.value.note)

            swap.value = confirming(t0 = now - 60)
            assertNull(vm.state.value.note)
        }

    @Test
    fun `what the deposit costs shows with its network fee`() =
        runTest {
            swap.value = confirming()
            start()

            val amounts = vm.state.value.amounts as StringResource.ByResource
            assertEquals(stringRes(Zatoshi(TOTAL_ZAT)), amounts.args.first())
        }

    @Test
    fun `calling it off offline says it didn't, and a second tap waits for the first`() =
        runTest {
            val offline = CompletableDeferred<Unit>()
            coEvery { swaps.abandon() } coAnswers { offline.await() }
            swap.value =
                AtomicSwapState(record(acceptedAt = now - 120), wait = AtomicSwapStep.Waiting(AtomicSwapWait.OPENING))
            start()

            val callOff = checkNotNull(vm.state.value.callOff)
            callOff.onClick()
            callOff.onClick()
            assertTrue(checkNotNull(vm.state.value.callOff).isLoading)
            assertFalse(checkNotNull(vm.state.value.callOff).isEnabled)
            offline.completeExceptionally(RpcException.TransportError("eth_getLogs", IOException("offline")))

            coVerify(exactly = 1) { swaps.abandon() }
            assertEquals(stringRes(R.string.convert_error_ethereum), vm.state.value.error)
            assertTrue(checkNotNull(vm.state.value.callOff).isEnabled)
        }

    @Test
    fun `without a conversion here to show, the reverse one under way opens instead`() =
        runTest {
            history.value = emptyList()
            coEvery { reverse.isUnderWay() } returns true

            start()

            verify { navigation.replace(PrivateUsdReverseArgs) }
        }

    @Test
    fun `without any conversion under way, a new one can be set up`() =
        runTest {
            history.value = emptyList()

            start()

            verify { navigation.replace(PrivateUsdConvertArgs) }
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val balances =
            mockk<PrivateUsdBalanceRepository> {
                every { state } returns MutableStateFlow(PrivateUsdBalanceState())
                every { observe() } returns MutableStateFlow(PrivateUsdBalanceState())
            }
        val currency = mockk<ObserveLocalCurrencyUseCase>()
        every { currency() } returns flowOf(LocalCurrency.DOLLAR)
        vm = PrivateUsdProgressVM(swaps, reverse, balances, currency, navigation, mockk(relaxed = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private fun confirming(
        t0: Long = now + 600,
        problem: AtomicSwapProblem? = null
    ) = AtomicSwapState(
        record = record().copy(deposit = SwapDeposit.Recorded(ZcashTxId.parse("aa".repeat(32)))),
        wait = AtomicSwapStep.Waiting(AtomicSwapWait.CONFIRMING, t0 = t0, t1 = t0 + 600),
        problem = problem,
        confirmations = AtomicSwapTestnet.deployment.makerConfirmations,
    )

    private fun record(acceptedAt: Long = now) =
        toUsd(index = 7, at = acceptedAt, outcome = null).copy(maxTotalZat = TOTAL_ZAT)

    private companion object {
        const val TOTAL_ZAT = 212_021L
    }
}
