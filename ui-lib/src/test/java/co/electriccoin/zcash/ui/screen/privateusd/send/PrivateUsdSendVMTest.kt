// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.ObserveDollarRateUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendCost
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendLog
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSender
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunSent
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdSendVMTest {
    private val token = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar }
    private val balance = MutableStateFlow(balance(AVAILABLE))
    private val sender = mockk<PrivateUsdSender>()
    private val biometrics = mockk<BiometricRepository>()
    private val sendLog = mockk<PrivateUsdSendLog>()
    private lateinit var vm: PrivateUsdSendVM

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val balanceRepository = mockk<PrivateUsdBalanceRepository>()
        every { balanceRepository.state } returns balance
        every { balanceRepository.observe() } returns balance
        val senders = mockk<PrivateUsdSenders>()
        every { senders.current } returns sender
        every { sender.usesTestAccount } returns true
        coEvery { sender.cost(any()) } returns PrivateUsdSendCost(BigInteger.ZERO, null)
        coEvery { sender.send(any()) } returns RailgunSent(TX_HASH, null)
        coEvery { biometrics.requestBiometrics(any()) } returns Unit
        coEvery { sendLog.add(any()) } returns Unit
        val wallet = mockk<RailgunWalletRepository>()
        val walletState = mockk<RailgunWalletState>()
        every { walletState.proof } returns null
        every { wallet.state } returns MutableStateFlow(walletState)
        val swaps = mockk<AtomicSwapRepository>()
        every { swaps.deployment } returns null
        val rates = mockk<ObserveDollarRateUseCase>()
        every { rates() } returns flowOf(null)
        vm =
            PrivateUsdSendVM(
                PrivateUsdSendArgs(true),
                balanceRepository,
                senders,
                wallet,
                swaps,
                rates,
                biometrics,
                sendLog,
                mockk(relaxed = true)
            )
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `repeated confirmation taps authorize and send only once`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            val authorized = CompletableDeferred<Unit>()
            coEvery { biometrics.requestBiometrics(any()) } coAnswers { authorized.await() }
            val confirm = vm.state.value.primaryButton.onClick

            confirm()
            confirm()
            assertFalse(vm.state.value.primaryButton.isEnabled)
            authorized.complete(Unit)
            confirm()

            coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
            coVerify(exactly = 1) { sender.send(any()) }
            assertEquals(PrivateUsdSendPhase.DONE, vm.state.value.phase)
        }

    @Test
    fun `sending the full balance retains success details after the balance refresh`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            coEvery { sender.send(any()) } coAnswers {
                balance.value = balance(BigInteger.ZERO)
                RailgunSent(TX_HASH, null)
            }

            vm.state.value.primaryButton
                .onClick()

            assertEquals(PrivateUsdSendPhase.DONE, vm.state.value.phase)
            assertNotNull(vm.state.value.done)
        }

    @Test
    fun `a corrupt activity log does not turn a successful send into a retry`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            coEvery { sendLog.add(any()) } throws StoreCorruptedException("unreadable")

            vm.state.value.primaryButton
                .onClick()

            assertEquals(PrivateUsdSendPhase.DONE, vm.state.value.phase)
            assertNotNull(vm.state.value.done)
            coVerify(exactly = 1) { sender.send(any()) }
        }

    @Test
    fun `cancelled authorization leaves the review available without sending`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            coEvery { biometrics.requestBiometrics(any()) } throws BiometricsCancelledException()

            vm.state.value.primaryButton
                .onClick()

            assertEquals(PrivateUsdSendPhase.REVIEW, vm.state.value.phase)
            assertFalse(vm.state.value.isBusy)
            coVerify(exactly = 0) { sender.send(any()) }
        }

    @Test
    fun `a changing balance cannot silently select another token for the reviewed send`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            val confirm = vm.state.value.primaryButton.onClick
            val otherToken = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar && it != token }
            balance.value =
                PrivateUsdBalanceState(
                    balances = PrivateUsdBalances(listOf(PrivateUsdAsset(otherToken, AVAILABLE)))
                )

            confirm()

            assertFalse(vm.state.value.primaryButton.isEnabled)
            assertNotNull(vm.state.value.review)
            coVerify(exactly = 0) { sender.send(any()) }
        }

    private fun review() {
        vm.state.value.amount
            .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
        vm.state.value.onRecipientChange(RECIPIENT)
        vm.state.value.primaryButton
            .onClick()
        assertEquals(PrivateUsdSendPhase.REVIEW, vm.state.value.phase)
    }

    private fun balance(available: BigInteger) =
        PrivateUsdBalanceState(
            balances = PrivateUsdBalances(listOf(PrivateUsdAsset(token, available)))
        )

    private companion object {
        val AVAILABLE: BigInteger = BigInteger.TEN.pow(6)
        const val RECIPIENT = "0x09ed1f966745be18c711c346242c0974dad7c3e5"
        const val TX_HASH = "0x1234"
    }
}
