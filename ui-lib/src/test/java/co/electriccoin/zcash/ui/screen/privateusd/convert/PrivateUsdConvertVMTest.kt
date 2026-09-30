// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.offer
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.requested
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseArgs
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
import kotlinx.coroutines.flow.map
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
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapService
import xyz.justzappit.offramp.p2p.Usdc6
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdConvertVMTest {
    private val swaps =
        mockk<AtomicSwapRepository>(relaxed = true) {
            every { deployment } returns AtomicSwapTestnet.deployment
            coEvery { isUnderWay() } returns false
            coEvery { quote(any()) } answers { quoteFor(requested()) }
        }
    private val reverse = mockk<ReverseSwapRepository> { coEvery { isUnderWay() } returns false }
    private val auth =
        mockk<SecretAuthGate> {
            every { pinPrompt } returns MutableStateFlow<PinVerifyState?>(null)
            coEvery { authenticate(any(), any()) } returns true
        }
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private val spendable = MutableStateFlow(Zatoshi(ONE_ZEC))
    private val currency = MutableStateFlow(LocalCurrency.DOLLAR)
    private lateinit var vm: PrivateUsdConvertVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a typed amount is quoted once its price is known, and reviewed as it costs in all`() =
        runTest {
            start()

            type("0.5")

            val quote = checkNotNull(vm.state.value.quote)
            assertEquals(stringRes(Zatoshi(49_999_999)), quote.pay)
            assertEquals(stringRes(Zatoshi(FEE_ZAT)), quote.networkFee)
            assertTrue(vm.state.value.primaryButton.isEnabled)
            coVerify(exactly = 1) { swaps.quote(AtomicSwapTestnet.deployment.minAmount) }
            coVerify(exactly = 2) { swaps.quote(any()) }

            type("0.4")

            coVerify(exactly = 3) { swaps.quote(any()) }
        }

    @Test
    fun `more than the wallet can spend is said at once, without asking for a quote`() =
        runTest {
            start()

            type("2")

            assertEquals(stringRes(R.string.convert_insufficient, stringRes(Zatoshi(ONE_ZEC))), vm.state.value.message)
            assertTrue(vm.state.value.isAmountInvalid)
            coVerify(exactly = 0) { swaps.quote(any()) }
        }

    @Test
    fun `a zero still being typed isn't flagged or quoted`() =
        runTest {
            start()

            for (typed in listOf("0", "0.0")) type(typed)

            assertFalse(vm.state.value.isAmountInvalid)
            assertNull(vm.state.value.message)
            coVerify(exactly = 0) { swaps.quote(any()) }
        }

    @Test
    fun `the maximum can't change an amount once the review is up`() =
        runTest {
            start()
            type("0.5")
            val review = vm.state.value.primaryButton.onClick
            val maximum = CompletableDeferred<Unit>()
            coEvery { swaps.quote(any()) } coAnswers {
                maximum.await()
                quoteFor(requested())
            }

            checkNotNull(vm.state.value.onMax).invoke()
            assertFalse(vm.state.value.primaryButton.isEnabled)
            review()
            assertEquals(PrivateUsdConvertPhase.AMOUNT, vm.state.value.phase)
            maximum.complete(Unit)
            runCurrent()

            assertEquals(PrivateUsdConvertPhase.AMOUNT, vm.state.value.phase)
            assertEquals(BigDecimal("0.60010000"), vm.state.value.amount.innerState.amount)
            assertEquals(
                stringRes(Zatoshi(60_010_000)),
                vm.state.value.quote
                    ?.pay
            )
            coVerify { swaps.quote(AtomicSwapTestnet.deployment.maxAmount) }
        }

    @Test
    fun `an offer the wallet can't price the fee of can't be reviewed, and says why`() =
        runTest {
            coEvery { swaps.quote(any()) } answers { quoteFor(requested(), feeZat = null) }
            start()

            type("0.5")

            assertNull(vm.state.value.quote)
            assertEquals(stringRes(R.string.convert_error_unpayable), vm.state.value.message)
            assertFalse(vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `without a rate amounts show in dollars and the conversion can still go ahead`() =
        runTest {
            start()

            type("0.5")

            assertEquals("$", vm.state.value.currencySymbol)
            assertEquals(BigDecimal("16.16"), vm.state.value.receiveEstimate.amount)
            assertTrue(vm.state.value.primaryButton.isEnabled)

            currency.value = LocalCurrency(FiatCurrency("INR"), "₹", BigDecimal("83.5"))

            assertEquals("₹", vm.state.value.currencySymbol)
            assertEquals(BigDecimal("1349.65"), vm.state.value.receiveEstimate.amount)
        }

    @Test
    fun `each way a quote fails says so in its own words`() =
        runTest {
            start()
            val failures =
                mapOf(
                    AtomicSwapHttpException.Unreachable(AtomicSwapService.RELAYER, IOException("down")) to
                        R.string.convert_error_relayer,
                    AtomicSwapHttpException.Unreachable(AtomicSwapService.MAKER, IOException("down")) to
                        R.string.convert_error_maker,
                    StoreCorruptedException("unreadable") to R.string.convert_error_store,
                    AtomicSwapBlockedException(AtomicSwapBlock.ZCASH_UNAVAILABLE, "no height") to
                        R.string.convert_error_wallet,
                    IllegalStateException("anything else") to R.string.convert_error_generic,
                )

            for ((failure, message) in failures) {
                coEvery { swaps.quote(any()) } throws failure
                type("0.5")
                assertEquals(stringRes(message), vm.state.value.message)
            }
        }

    @Test
    fun `a quote refused because a reverse conversion is under way goes to it`() =
        runTest {
            start()
            coEvery { swaps.quote(any()) } throws AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "reverse")
            coEvery { reverse.isUnderWay() } returns true

            type("0.5")

            verify { navigation.replace(PrivateUsdReverseArgs) }
        }

    @Test
    fun `a conversion found under way on opening goes straight to its progress`() =
        runTest {
            coEvery { swaps.isUnderWay() } returns true

            start()

            verify { navigation.replace(PrivateUsdProgressArgs) }
        }

    @Test
    fun `nothing is accepted until the app lock says so`() =
        runTest {
            start()
            type("0.5")
            vm.state.value.primaryButton
                .onClick()
            coEvery { auth.authenticate(any(), any()) } returns false

            vm.state.value.primaryButton
                .onClick()

            coVerify(exactly = 0) { swaps.accept(any()) }
            assertEquals(PrivateUsdConvertPhase.REVIEW, vm.state.value.phase)
            coEvery { auth.authenticate(any(), any()) } returns true

            vm.state.value.primaryButton
                .onClick()

            coVerify(exactly = 1) { swaps.accept(any()) }
            verify { navigation.replace(PrivateUsdProgressArgs) }
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val accounts =
            mockk<AccountDataSource> {
                every { zashiAccount } returns
                    spendable.map { mockk<ZashiAccount> { every { spendableShieldedBalance } returns it } }
            }
        val balances =
            mockk<PrivateUsdBalanceRepository> {
                every { state } returns MutableStateFlow(PrivateUsdBalanceState())
                every { observe() } returns MutableStateFlow(PrivateUsdBalanceState())
            }
        val localCurrency = mockk<ObserveLocalCurrencyUseCase>()
        every { localCurrency() } returns currency
        vm =
            PrivateUsdConvertVM(
                atomicSwapRepository = swaps,
                reverseSwapRepository = reverse,
                secretAuthGate = auth,
                navigationRouter = navigation,
                navigateBackToPay = mockk(relaxed = true),
                accountDataSource = accounts,
                balanceRepository = balances,
                observeLocalCurrency = localCurrency,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private fun TestScope.type(amount: String) {
        vm.state.value.amount
            .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal(amount)))
        advanceTimeBy(TYPING.plus(1.milliseconds))
        runCurrent()
    }

    private companion object {
        const val ONE_ZEC = 100_000_000L
        const val ZAT_PER_UNIT = 3L
        const val FEE_ZAT = 10_000L
        val TYPING = 700.milliseconds

        fun quoteFor(
            requested: Usdc6,
            feeZat: Long? = FEE_ZAT
        ): AtomicSwapQuote {
            val units = requested.micros.toLong()
            return AtomicSwapQuote(
                offer(
                    index = units.toInt(),
                    requested = units,
                    depositZat = units * ZAT_PER_UNIT,
                    expiresAt = Clock.System.now().epochSeconds + 300,
                    receives = units * 97L / 100,
                ),
                feeZat,
            )
        }
    }
}
