// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.atomicswap.DepositFee
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendStatus
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.TextSelection
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
import kotlinx.coroutines.flow.combine
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
import xyz.justzappit.offramp.atomicswap.SwapErrorCode
import xyz.justzappit.offramp.p2p.Usdc6
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    // The whole shielded balance; none means all of it is spendable.
    private val shielded = MutableStateFlow<Zatoshi?>(null)
    private val sync = MutableStateFlow<WalletSnapshot?>(walletAt(Synchronizer.Status.SYNCED))
    private val currency = MutableStateFlow(LocalCurrency.DOLLAR)
    private val privateBalance = MutableStateFlow(PrivateUsdBalanceState())
    private val spending = MutableStateFlow(PrivateUsdSpendStatus.AVAILABLE)
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
            coVerify(exactly = 1) { swaps.quote(PrivateUsdZecQuotes.PRICE_PROBE) }
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
            assertEquals(BigDecimal("1.00000000"), vm.state.value.amount.innerState.amount)
            assertEquals(
                stringRes(Zatoshi(ONE_ZEC)),
                vm.state.value.quote
                    ?.pay
            )
            coVerify { swaps.quote(Usdc6.ofMicros(33_330_000)) }
        }

    @Test
    fun `the maximum fills in the whole balance at once, then what its quote pays for`() =
        runTest {
            spendable.value = Zatoshi(ONE_ZEC + 2)
            start()
            val maximum = CompletableDeferred<Unit>()
            coEvery { swaps.quote(any()) } coAnswers {
                maximum.await()
                quoteFor(requested())
            }

            checkNotNull(vm.state.value.onMax).invoke()

            assertEquals(BigDecimal("1.00000002"), vm.state.value.amount.innerState.amount)
            assertTrue(vm.state.value.isQuoting)
            maximum.complete(Unit)
            runCurrent()
            assertEquals(BigDecimal("1.00000000"), vm.state.value.amount.innerState.amount)
            assertEquals(TextSelection.End, vm.state.value.amount.innerState.innerTextFieldState.selection)
            assertFalse(vm.state.value.isQuoting)
            coVerify { swaps.quote(Usdc6.ofMicros(33_330_000)) }
        }

    @Test
    fun `while a changed amount is quoted the last quote stays up, and can't be reviewed`() =
        runTest {
            start()
            type("0.5")
            val first = checkNotNull(vm.state.value.quote)
            val next = CompletableDeferred<Unit>()
            coEvery { swaps.quote(any()) } coAnswers {
                next.await()
                quoteFor(requested())
            }

            type("0.6")

            assertTrue(vm.state.value.isQuoting)
            assertEquals(first, vm.state.value.quote)
            assertFalse(vm.state.value.primaryButton.isEnabled)
            next.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.isQuoting)
            assertNotEquals(first.pay, checkNotNull(vm.state.value.quote).pay)
            assertTrue(vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `while the wallet syncs, the balance it hasn't caught up on is said, and the maximum waits for it`() =
        runTest {
            shielded.value = Zatoshi(2 * ONE_ZEC)
            sync.value = walletAt(Synchronizer.Status.SYNCING, progress = 0.42f)
            start()

            assertEquals(stringRes(R.string.convert_zec_syncing_progress, 42), vm.state.value.zecSyncing)
            assertNull(vm.state.value.onMax)

            sync.value = walletAt(Synchronizer.Status.SYNCED)
            assertNull(vm.state.value.zecSyncing)
            assertNotNull(vm.state.value.onMax)
        }

    @Test
    fun `a deposit that takes more than one transaction can't be reviewed, and says why`() =
        runTest {
            coEvery { swaps.quote(any()) } answers { quoteFor(requested(), DepositFee.Split) }
            start()

            type("0.5")

            assertNull(vm.state.value.quote)
            assertEquals(stringRes(R.string.convert_error_unpayable), vm.state.value.message)
            assertFalse(vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `a wallet that can't propose the deposit before it syncs says so, and quotes once it has`() =
        runTest {
            sync.value = walletAt(Synchronizer.Status.SYNCING, progress = 0.3f)
            coEvery { swaps.quote(any()) } answers { quoteFor(requested(), DepositFee.Unready) }
            start()

            type("0.5")

            assertNull(vm.state.value.quote)
            assertEquals(stringRes(R.string.convert_quote_after_sync), vm.state.value.message)

            coEvery { swaps.quote(any()) } answers { quoteFor(requested()) }
            sync.value = walletAt(Synchronizer.Status.SYNCED)
            runCurrent()

            assertNull(vm.state.value.message)
            assertNotNull(vm.state.value.quote)
            assertTrue(vm.state.value.primaryButton.isEnabled)
        }

    @Test
    fun `a wallet unavailable response arriving after sync still gets a fresh quote`() =
        runTest {
            sync.value = walletAt(Synchronizer.Status.SYNCING, progress = 0.9f)
            val response = CompletableDeferred<Unit>()
            var requests = 0
            coEvery { swaps.quote(any()) } coAnswers {
                val amount = requested()
                requests++
                if (requests == 2) {
                    response.await()
                    quoteFor(amount, DepositFee.Unready)
                } else {
                    quoteFor(amount)
                }
            }
            start()
            type("0.5")
            assertTrue(vm.state.value.isQuoting)

            sync.value = walletAt(Synchronizer.Status.SYNCED)
            runCurrent()
            response.complete(Unit)
            runCurrent()

            assertNull(vm.state.value.message)
            assertNotNull(vm.state.value.quote)
            assertTrue(vm.state.value.primaryButton.isEnabled)
            coVerify(exactly = 3) { swaps.quote(any()) }
        }

    @Test
    fun `a late wallet unavailable response retries only once for the completed sync`() =
        runTest {
            sync.value = walletAt(Synchronizer.Status.SYNCING, progress = 0.9f)
            val response = CompletableDeferred<Unit>()
            var requests = 0
            coEvery { swaps.quote(any()) } coAnswers {
                val amount = requested()
                requests++
                if (requests == 2) response.await()
                quoteFor(amount, DepositFee.Unready)
            }
            start()
            type("0.5")

            sync.value = walletAt(Synchronizer.Status.SYNCED)
            runCurrent()
            response.complete(Unit)
            runCurrent()
            advanceTimeBy(TYPING)
            runCurrent()

            assertEquals(stringRes(R.string.convert_quote_after_sync), vm.state.value.message)
            assertFalse(vm.state.value.primaryButton.isEnabled)
            coVerify(exactly = 3) { swaps.quote(any()) }
        }

    @Test
    fun `the maximum, whose every note costs a dearer fee than a smaller deposit's, can still be reviewed`() =
        runTest {
            coEvery { swaps.quote(any()) } answers {
                val units = requested().micros.toLong()
                val shortZat = units * ZAT_PER_UNIT + WHOLE_BALANCE_FEE_ZAT - ONE_ZEC
                val fee =
                    when {
                        requested() == PrivateUsdZecQuotes.PRICE_PROBE -> DepositFee.Payable(FEE_ZAT)
                        shortZat > 0 -> DepositFee.Short(shortZat)
                        else -> DepositFee.Payable(WHOLE_BALANCE_FEE_ZAT)
                    }
                quoteFor(requested(), fee)
            }
            start()

            checkNotNull(vm.state.value.onMax).invoke()
            runCurrent()

            assertNull(vm.state.value.message)
            assertNotNull(vm.state.value.quote)
            assertEquals(BigDecimal("1.00000000"), vm.state.value.amount.innerState.amount)
            assertTrue(vm.state.value.primaryButton.isEnabled)
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
                    AtomicSwapHttpException.Refused(AtomicSwapService.RELAYER, 400, SwapErrorCode.REJECTED, "no") to
                        R.string.convert_error_relayer_refused,
                    AtomicSwapHttpException.Unreachable(AtomicSwapService.MAKER, IOException("down")) to
                        R.string.convert_error_maker,
                    AtomicSwapHttpException.Refused(AtomicSwapService.MAKER, 503, SwapErrorCode.UNAVAILABLE, "short") to
                        R.string.convert_error_maker_cant_fill,
                    AtomicSwapHttpException.Refused(AtomicSwapService.MAKER, 400, SwapErrorCode.REJECTED, "range") to
                        R.string.convert_error_amount_refused,
                    AtomicSwapBlockedException(AtomicSwapBlock.AMOUNT_UNQUOTABLE, "more than a quote can name") to
                        R.string.convert_error_amount_refused,
                    AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_DEVICE_REFUSED, "attestation refused") to
                        R.string.convert_error_device_refused,
                    StoreCorruptedException("unreadable") to R.string.convert_error_store,
                    AtomicSwapBlockedException(AtomicSwapBlock.ZCASH_UNAVAILABLE, "no height") to
                        R.string.convert_quote_after_sync,
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
    fun `the conversion being accepted shows progress without an earlier payment error`() =
        runTest {
            start()
            type("0.5")
            vm.state.value.primaryButton
                .onClick()
            val accepted = CompletableDeferred<Unit>()
            coEvery { swaps.accept(any()) } coAnswers {
                spending.value = PrivateUsdSpendStatus.CONVERTING
                accepted.await()
                mockk(relaxed = true)
            }

            vm.state.value.primaryButton
                .onClick()
            assertTrue(vm.state.value.primaryButton.isLoading)
            assertFalse(vm.state.value.primaryButton.isEnabled)
            assertNull(vm.state.value.message)
            spending.value = PrivateUsdSpendStatus.SENDING
            assertNull(vm.state.value.message)

            accepted.complete(Unit)
            runCurrent()
            coVerify(exactly = 1) { swaps.accept(any()) }
            verify { navigation.replace(PrivateUsdProgressArgs) }
        }

    @Test
    fun `an earlier unresolved send still blocks starting a conversion`() =
        runTest {
            start()
            type("0.5")
            val review = vm.state.value.primaryButton.onClick
            spending.value = PrivateUsdSpendStatus.SENDING

            assertFalse(vm.state.value.primaryButton.isEnabled)
            assertEquals(stringRes(R.string.private_usd_payment_pending), vm.state.value.message)
            review()
            assertEquals(PrivateUsdConvertPhase.AMOUNT, vm.state.value.phase)
            coVerify(exactly = 0) { swaps.accept(any()) }
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

    @Test
    fun `unknown balances load visibly while cached balances stay visible during refresh`() =
        runTest {
            start()
            assertTrue(vm.state.value.isUsdBalanceLoading)
            privateBalance.value =
                PrivateUsdBalanceState(balances = PrivateUsdBalances(emptyList()), isRefreshing = true)
            assertFalse(vm.state.value.isUsdBalanceLoading)
            assertNotNull(vm.state.value.usdAvailable)
        }

    @Test
    fun `a failed first balance load stops loading and offers an error`() =
        runTest {
            start()
            privateBalance.value = PrivateUsdBalanceState(refreshFailed = true)
            assertFalse(vm.state.value.isUsdBalanceLoading)
            assertEquals(stringRes(R.string.private_usd_load_failed), vm.state.value.usdBalanceError)
            privateBalance.value = privateBalance.value.copy(isRefreshing = true)
            assertTrue(vm.state.value.isUsdBalanceLoading)
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val accounts =
            mockk<AccountDataSource> {
                every { zashiAccount } returns
                    combine(spendable, shielded) { available, total ->
                        mockk<ZashiAccount> {
                            every { spendableShieldedBalance } returns available
                            every { totalShieldedBalance } returns (total ?: available)
                        }
                    }
            }
        val balances =
            mockk<PrivateUsdBalanceRepository> {
                every { state } returns privateBalance
                every { observe() } returns privateBalance
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
                walletSnapshotDataSource = mockk { every { observe() } returns sync },
                spendGuard =
                    mockk<PrivateUsdSpendGuard> {
                        every { state } returns spending
                    },
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
        const val WHOLE_BALANCE_FEE_ZAT = 25_000L
        val TYPING = 700.milliseconds

        fun walletAt(
            status: Synchronizer.Status,
            progress: Float = 1f
        ) = WalletSnapshot(
            status = status,
            progress = PercentDecimal(progress),
            synchronizerError = null,
            isSpendable = true,
            restoringState = WalletRestoringState.NONE,
        )

        fun quoteFor(
            requested: Usdc6,
            fee: DepositFee = DepositFee.Payable(FEE_ZAT)
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
                fee,
            )
        }
    }
}
