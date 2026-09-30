// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapProblem
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapState
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.JointAccountId
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.p2p.Usdc6
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdReverseVMTest {
    private val conversion = MutableStateFlow(ReverseSwapState())
    private val kept = MutableStateFlow<List<ReverseSwapRecord>>(emptyList())
    private val repository =
        mockk<ReverseSwapRepository>(relaxed = true) {
            every { state } returns conversion
            every { history } returns kept
            coEvery { canRescue(any()) } returns false
        }
    private val auth =
        mockk<SecretAuthGate> {
            every { pinPrompt } returns MutableStateFlow<PinVerifyState?>(null)
            coEvery { authenticate(any(), any()) } returns true
        }
    private val currency = MutableStateFlow(LocalCurrency.DOLLAR)
    private val swaps =
        mockk<AtomicSwapRepository>(relaxed = true) { every { deployment } returns AtomicSwapTestnet.deployment }
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private lateinit var vm: PrivateUsdReverseVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a quote waiting on the wallet stops loading and says why`() =
        runTest {
            coEvery { repository.quote(any()) } throws
                AtomicSwapBlockedException(AtomicSwapBlock.ZCASH_UNAVAILABLE, "the chain's height is unknown")
            start()

            type("10")

            assertFalse(vm.state.value.isQuoting)
            assertFalse(vm.state.value.primary.isLoading)
            assertEquals(stringRes(R.string.convert_error_wallet), vm.state.value.error)
        }

    @Test
    fun `a conversion into private USD under way sends the quote to its progress rather than failing it`() =
        runTest {
            coEvery { repository.quote(any()) } throws
                AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a conversion into private USD is under way")
            coEvery { swaps.isUnderWay() } returns true
            start()

            type("10")

            coVerify { navigation.replace(PrivateUsdProgressArgs) }
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.isQuoting)
        }

    @Test
    fun `a rate arriving after typing began doesn't change what the typed amount means`() =
        runTest {
            start()

            type("10")
            currency.value = LocalCurrency(FiatCurrency("INR"), "₹", BigDecimal("83.5"))
            advanceTimeBy(TYPING)
            runCurrent()

            assertEquals("$", vm.state.value.currencySymbol)
            coVerify(exactly = 1) { repository.quote(Usdc6.ofMicros(10_000_000)) }
            coVerify(exactly = 1) { repository.quote(any()) }

            type(null)

            assertEquals("₹", vm.state.value.currencySymbol)
        }

    @Test
    fun `an approval that fails shows on the progress screen`() =
        runTest {
            conversion.value = ReverseSwapState(record(ReversePhase.AWAITING_READY))
            coEvery { repository.ready(any()) } throws RpcException.TransportError("eth_call", IOException("offline"))
            start()

            checkNotNull(checkNotNull(vm.state.value.progress).primaryButton).onClick()

            assertEquals(
                stringRes(R.string.convert_error_ethereum),
                vm.state.value.progress
                    ?.error
            )
        }

    @Test
    fun `nothing is approved until the app lock says so`() =
        runTest {
            conversion.value = ReverseSwapState(record(ReversePhase.AWAITING_READY))
            coEvery { auth.authenticate(any(), any()) } returns false
            start()

            checkNotNull(checkNotNull(vm.state.value.progress).primaryButton).onClick()

            coVerify(exactly = 0) { repository.ready(any()) }
            assertNull(
                vm.state.value.progress
                    ?.error
            )
        }

    @Test
    fun `checks that keep failing in the background show as a problem`() =
        runTest {
            conversion.value = ReverseSwapState(record(ReversePhase.RECEIVING_ZEC), AtomicSwapProblem.UNEXPECTED)
            start()

            val problem =
                checkNotNull(
                    vm.state.value.progress
                        ?.problem
                )
            assertEquals(stringRes(R.string.convert_problem_unexpected), problem.message)
            assertNull(problem.onRetry)
        }

    @Test
    fun `a refund that can be recovered offers to`() =
        runTest {
            conversion.value = ReverseSwapState(record(ReversePhase.REFUNDED))
            kept.value = listOf(record(ReversePhase.REFUNDED))
            coEvery { repository.canRescue(any()) } returns true
            start()

            assertEquals(
                stringRes(R.string.reverse_rescue),
                vm.state.value.progress
                    ?.callOff
                    ?.text
            )
        }

    @Test
    fun `what's received shows as the ZEC the sweep brings, never below nothing`() =
        runTest {
            conversion.value = ReverseSwapState(record(ReversePhase.SETTLING, depositZat = 5_000))
            start()

            val amounts =
                vm.state.value.progress
                    ?.amounts as StringResource.ByResource
            assertEquals(stringRes(Zatoshi(0)), amounts.args.last())
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val token = AtomicSwapTestnet.deployment.privateUsdToken
        val accounts =
            mockk<AccountDataSource> {
                every { zashiAccount } returns
                    flowOf(mockk<ZashiAccount> { every { spendableShieldedBalance } returns Zatoshi(0) })
            }
        val funds =
            PrivateUsdBalanceState(
                balances =
                    PrivateUsdBalances(listOf(PrivateUsdAsset(token, available = BigInteger.valueOf(50_000_000)))),
            )
        val balances =
            mockk<PrivateUsdBalanceRepository> {
                every { state } returns MutableStateFlow(funds)
                every { observe() } returns MutableStateFlow(funds)
            }
        val localCurrency = mockk<ObserveLocalCurrencyUseCase>()
        every { localCurrency() } returns currency
        vm =
            PrivateUsdReverseVM(
                repository = repository,
                secretAuthGate = auth,
                navigationRouter = navigation,
                navigateBackToPay = mockk(relaxed = true),
                atomicSwapRepository = swaps,
                railgunWalletRepository = mockk(relaxed = true),
                balanceRepository = balances,
                observeLocalCurrency = localCurrency,
                accountDataSource = accounts,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private fun TestScope.type(amount: String?) {
        vm.state.value.amount.onValueChange(
            amount?.let { NumberTextFieldInnerState.fromAmount(BigDecimal(it)) } ?: NumberTextFieldInnerState()
        )
        advanceTimeBy(TYPING)
        runCurrent()
    }

    private fun record(
        phase: ReversePhase,
        depositZat: Long = 100_000,
    ) = toZec(index = 3, phase, depositZat = depositZat).copy(account = JointAccountId.parse("0a0b".repeat(8)))

    private companion object {
        val TYPING = 701.milliseconds
    }
}
