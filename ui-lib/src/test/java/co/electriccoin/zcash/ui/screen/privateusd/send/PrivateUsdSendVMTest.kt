// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendCost
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendOutcome
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSender
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendStatus
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
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
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunNetwork
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdSendVMTest {
    private val token = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar }
    private val balance = MutableStateFlow(balance(AVAILABLE))
    private val spending = MutableStateFlow(PrivateUsdSpendStatus.AVAILABLE)
    private val sender = mockk<PrivateUsdSender>()
    private val pin = MutableStateFlow<PinVerifyState?>(null)
    private val auth =
        mockk<SecretAuthGate> {
            every { pinPrompt } returns pin
            coEvery { authenticate(any(), any()) } returns true
        }
    private lateinit var vm: PrivateUsdSendVM

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val balanceRepository = mockk<PrivateUsdBalanceRepository>()
        every { balanceRepository.state } returns balance
        every { balanceRepository.observe() } returns balance
        val senders = mockk<PrivateUsdSenders>()
        every { senders.current } returns sender
        coEvery { sender.cost(any()) } returns PrivateUsdSendCost(BigInteger.ZERO, 0, BigInteger.ZERO, Sepolia.TEST_USD)
        every { sender.networkFeeToken } returns Sepolia.TEST_USD
        every { sender.maxNetworkFee } returns BigInteger.ZERO
        coEvery { sender.send(any()) } returns PrivateUsdSendOutcome.Sent(TX_HASH)
        val wallet = mockk<RailgunWalletRepository>()
        every { wallet.state } returns
            MutableStateFlow(RailgunWalletState(RailgunWalletState.Phase.READY, RailgunNetwork.SEPOLIA))
        val swaps = mockk<AtomicSwapRepository>()
        every { swaps.deployment } returns null
        val rates = mockk<ObserveLocalCurrencyUseCase>()
        every { rates() } returns flowOf(LocalCurrency.DOLLAR)
        vm =
            PrivateUsdSendVM(
                PrivateUsdSendArgs(PrivateUsdSendMode.WITHDRAW),
                balanceRepository,
                senders,
                wallet,
                swaps,
                rates,
                auth,
                mockk(relaxed = true),
                mockk<PrivateUsdSpendGuard> {
                    every { state } returns spending
                }
            )
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `an unresolved payment disables confirmation even through a previously captured callback`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            val confirm = vm.state.value.primaryButton.onClick

            spending.value = PrivateUsdSpendStatus.SENDING
            assertFalse(vm.state.value.primaryButton.isEnabled)
            assertNotNull(vm.state.value.error)
            confirm()
            coVerify(exactly = 0) { auth.authenticate(any(), any()) }
            coVerify(exactly = 0) { sender.send(any()) }

            spending.value = PrivateUsdSpendStatus.AVAILABLE
            assertTrue(vm.state.value.primaryButton.isEnabled)
            assertNull(vm.state.value.error)
            confirm()
            coVerify(exactly = 1) { sender.send(any()) }
        }

    @Test
    fun `repeated confirmation taps authorize and send only once`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            val authorized = CompletableDeferred<Boolean>()
            coEvery { auth.authenticate(any(), any()) } coAnswers { authorized.await() }
            val confirm = vm.state.value.primaryButton.onClick

            confirm()
            confirm()
            assertFalse(vm.state.value.primaryButton.isEnabled)
            authorized.complete(true)
            confirm()

            coVerify(exactly = 1) { auth.authenticate(any(), any()) }
            coVerify(exactly = 1) { sender.send(any()) }
            assertEquals(PrivateUsdSendPhase.DONE, vm.state.value.phase)
        }

    @Test
    fun `the app's PIN is asked for over the review before anything is sent`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            val entered = CompletableDeferred<Boolean>()
            val prompt = PinVerifyState(hasError = false, lockoutSecondsRemaining = 0, onPinSubmit = {}, onCancel = {})
            coEvery { auth.authenticate(any(), any()) } coAnswers {
                pin.value = prompt
                entered.await().also { pin.value = null }
            }

            vm.state.value.primaryButton
                .onClick()

            assertEquals(prompt, vm.state.value.pinVerify)
            coVerify(exactly = 0) { sender.send(any()) }
            entered.complete(true)
            assertNull(vm.state.value.pinVerify)
            coVerify(exactly = 1) { sender.send(any()) }
        }

    @Test
    fun `sending the full balance retains success details after the balance refresh`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            coEvery { sender.send(any()) } coAnswers {
                balance.value = balance(BigInteger.ZERO)
                PrivateUsdSendOutcome.Sent(TX_HASH)
            }

            vm.state.value.primaryButton
                .onClick()

            assertEquals(PrivateUsdSendPhase.DONE, vm.state.value.phase)
            assertNull(checkNotNull(vm.state.value.done).note)
        }

    @Test
    fun `a send that may have gone out never goes back to review`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            coEvery { sender.send(any()) } returns PrivateUsdSendOutcome.Unconfirmed(TX_HASH)

            vm.state.value.primaryButton
                .onClick()

            assertEquals(PrivateUsdSendPhase.DONE, vm.state.value.phase)
            assertEquals(stringRes(R.string.private_usd_send_unconfirmed), checkNotNull(vm.state.value.done).note)
            assertEquals(stringRes(R.string.convert_result_done), vm.state.value.primaryButton.text)
        }

    @Test
    fun `a send that left nothing behind can be confirmed again, and says why`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            coEvery { sender.send(any()) } returns PrivateUsdSendOutcome.NotSent

            vm.state.value.primaryButton
                .onClick()

            assertEquals(PrivateUsdSendPhase.REVIEW, vm.state.value.phase)
            assertEquals(stringRes(R.string.private_usd_send_not_sent), vm.state.value.error)
            assertTrue(vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `a review the engine can't price stays on the form, with nothing sent`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            coEvery { sender.cost(any()) } throws RailgunException.Unavailable(IllegalStateException("updating"))

            vm.state.value.amount
                .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
            vm.state.value.recipient
                .onValueChange(RECIPIENT)
            vm.state.value.primaryButton
                .onClick()

            assertEquals(PrivateUsdSendPhase.FORM, vm.state.value.phase)
            assertEquals(stringRes(R.string.private_usd_send_not_sent), vm.state.value.error)
            assertFalse(vm.state.value.isBusy)
        }

    @Test
    fun `cancelled authorization leaves the review available without sending`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()
            coEvery { auth.authenticate(any(), any()) } returns false

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

    @Test
    fun `a balance refresh can't swap the token the form started on for another`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            val otherToken = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar && it != token }

            balance.value =
                PrivateUsdBalanceState(
                    balances =
                        PrivateUsdBalances(
                            listOf(PrivateUsdAsset(otherToken, AVAILABLE), PrivateUsdAsset(token, BigInteger.ZERO))
                        )
                )
            vm.state.value.amount
                .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))

            val selected =
                vm.state.value.assets
                    .single { it.isSelected }
            assertEquals(token.symbol, selected.symbol)
            assertEquals(stringRes(R.string.private_usd_send_too_much), vm.state.value.amountNote)
            assertFalse(vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `a reviewed send the balance no longer covers says so`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            review()

            balance.value = balance(BigInteger.ONE)

            assertEquals(stringRes(R.string.private_usd_send_balance_dropped), vm.state.value.error)
            assertFalse(vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `editing the form clears what went wrong before`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            coEvery { sender.cost(any()) } throws RailgunException.Unavailable(IllegalStateException("updating"))
            vm.state.value.amount
                .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
            vm.state.value.recipient
                .onValueChange(RECIPIENT)
            vm.state.value.primaryButton
                .onClick()
            assertEquals(stringRes(R.string.private_usd_send_not_sent), vm.state.value.error)

            vm.state.value.amount
                .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal.TEN))

            assertNull(vm.state.value.error)
        }

    @Test
    fun `the recipient can't be edited while the send is being priced`() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            val priced = CompletableDeferred<PrivateUsdSendCost>()
            coEvery { sender.cost(any()) } coAnswers { priced.await() }
            vm.state.value.amount
                .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
            vm.state.value.recipient
                .onValueChange(RECIPIENT)

            vm.state.value.primaryButton
                .onClick()

            assertFalse(vm.state.value.recipient.isEnabled)
            priced.complete(PrivateUsdSendCost(BigInteger.ZERO, 0, BigInteger.ZERO, Sepolia.TEST_USD))
            assertEquals(PrivateUsdSendPhase.REVIEW, vm.state.value.phase)
        }

    private fun review() {
        vm.state.value.amount
            .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
        vm.state.value.recipient
            .onValueChange(RECIPIENT)
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
        val TX_HASH = TxHash.fromHex("0x" + "12".repeat(32))
    }
}
