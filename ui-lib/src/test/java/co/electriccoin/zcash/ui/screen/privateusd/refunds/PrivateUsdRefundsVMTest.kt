// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.refunds

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import xyz.justzappit.offramp.atomicswap.ReversePhase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdRefundsVMTest {
    private val history = MutableStateFlow(listOf(toZec(1, ReversePhase.REFUNDED), toZec(2, ReversePhase.REFUNDED)))
    private val repository = mockk<ReverseSwapRepository>(relaxed = true)
    private val auth =
        mockk<SecretAuthGate> {
            every { pinPrompt } returns MutableStateFlow<PinVerifyState?>(null)
            coEvery { authenticate(any(), any()) } returns true
        }
    private lateinit var vm: PrivateUsdRefundsVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `an older refund stays recoverable when the newest needs no recovery`() =
        runTest {
            coEvery { repository.canRescue(1) } returns true
            coEvery { repository.canRescue(2) } returns false
            start()

            assertEquals(
                listOf(2, 1),
                vm.state.value.refunds
                    .map { it.index }
            )
            assertNull(
                vm.state.value.refunds[0]
                    .recover
            )
            assertNotNull(
                vm.state.value.refunds[1]
                    .recover
            )
            vm.state.value.refunds[1]
                .recover
                ?.onClick
                ?.invoke()
            coVerify(exactly = 1) { repository.rescue(1) }
        }

    @Test
    fun `a failed check on one refund does not hide the others`() =
        runTest {
            coEvery { repository.canRescue(1) } returns true
            coEvery { repository.canRescue(2) } throws IllegalStateException("offline")
            start()

            assertEquals(
                stringRes(R.string.refunds_check_failed),
                vm.state.value.refunds[0]
                    .error
            )
            assertNotNull(
                vm.state.value.refunds[1]
                    .recover
            )
            coEvery { repository.canRescue(2) } returns true
            vm.state.value.onRefresh()
            assertNotNull(
                vm.state.value.refunds[0]
                    .recover
            )
        }

    @Test
    fun `cancelled authorization recovers nothing`() =
        runTest {
            coEvery { repository.canRescue(any()) } returns true
            coEvery { auth.authenticate(any(), any()) } returns false
            start()

            vm.state.value.refunds[1]
                .recover
                ?.onClick
                ?.invoke()
            coVerify(exactly = 0) { repository.rescue(any()) }
        }

    @Test
    fun `repeated taps cannot authorize two recoveries at once`() =
        runTest {
            val approved = CompletableDeferred<Boolean>()
            coEvery { repository.canRescue(any()) } returns true
            coEvery { auth.authenticate(any(), any()) } coAnswers { approved.await() }
            start()
            val first =
                checkNotNull(
                    vm.state.value.refunds[1]
                        .recover
                )
            val other =
                checkNotNull(
                    vm.state.value.refunds[0]
                        .recover
                )

            first.onClick()
            first.onClick()
            other.onClick()
            runCurrent()
            assertFalse(vm.state.value.isBackEnabled)
            approved.complete(true)
            runCurrent()

            coVerify(exactly = 1) { repository.rescue(1) }
            coVerify(exactly = 0) { repository.rescue(2) }
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        vm =
            PrivateUsdRefundsVM(
                records = mockk<ReverseSwapRecords> { every { observeHistory } returns history },
                repository = repository,
                auth = auth,
                observeLocalCurrency =
                    mockk<ObserveLocalCurrencyUseCase> {
                        every { this@mockk.invoke() } returns
                            flowOf(
                                LocalCurrency.DOLLAR
                            )
                    },
                navigation = mockk<NavigationRouter>(relaxed = true),
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }
}
