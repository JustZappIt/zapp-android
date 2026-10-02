// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapState
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdActivityUseCase
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdConversionUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendHistory
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendLog
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRecord
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendStatus
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunDestination
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdVMTest {
    private val token = AtomicSwapTestnet.deployment.privateUsdToken
    private val balance = MutableStateFlow(PrivateUsdBalanceState())
    private val forward = MutableStateFlow(AtomicSwapState())
    private val reverse = MutableStateFlow(ReverseSwapState())
    private val forwardHistory = MutableStateFlow(emptyList<AtomicSwapRecord>())
    private val reverseHistory = MutableStateFlow(emptyList<ReverseSwapRecord>())
    private val sends = MutableStateFlow(PrivateUsdSendHistory())
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private lateinit var vm: PrivateUsdVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `the headline is the home screen's figure, with what's available and what screening refused in rows`() =
        runTest {
            balance.value =
                funds(
                    PrivateUsdAsset(
                        token,
                        available = dollars("1.00"),
                        processing = dollars("0.50"),
                        arriving = dollars("0.25"),
                        blocked = dollars("2.00"),
                    )
                )
            start()

            val state = vm.state.value
            assertEquals(LocalCurrency.DOLLAR.format(BigDecimal("1.500000")).asPrivacySensitive(), state.headline)
            assertEquals(
                listOf(
                    R.string.private_usd_row_available,
                    R.string.private_usd_row_processing,
                    R.string.private_usd_row_arriving,
                    R.string.private_usd_row_blocked,
                ),
                state.rows.map { (it.label as StringResource.ByResource).resource },
            )
            assertEquals(LocalCurrency.DOLLAR.format(BigDecimal("1.000000")).asPrivacySensitive(), state.rows[0].amount)
            assertEquals(listOf(false, false, false, true), state.rows.map { it.isDanger })
        }

    @Test
    fun `a balance not known yet says it's loading, and a failed first load doesn't claim to show the last one`() =
        runTest {
            balance.value = PrivateUsdBalanceState(isRefreshing = true)
            start()

            assertEquals(stringRes(R.string.private_usd_home_loading), vm.state.value.headline)
            assertFalse(vm.state.value.isHeadlineKnown)

            balance.value = PrivateUsdBalanceState(refreshFailed = true)
            assertEquals(stringRes(R.string.private_usd_home_unknown), vm.state.value.headline)
            assertEquals(stringRes(R.string.private_usd_load_failed), vm.state.value.refreshError)

            balance.value = funds(PrivateUsdAsset(token, available = dollars("1"))).copy(refreshFailed = true)
            assertEquals(stringRes(R.string.private_usd_refresh_failed), vm.state.value.refreshError)
        }

    @Test
    fun `the time it was updated says the day too`() =
        runTest {
            val at = Instant.fromEpochSeconds(1_790_000_000)
            balance.value = funds(PrivateUsdAsset(token, available = dollars("1"))).copy(updatedAt = at)
            start()

            assertEquals(stringRes(R.string.private_usd_updated, dateTime(at)), vm.state.value.status)
        }

    @Test
    fun `activity merges conversions both ways with sends, newest first`() =
        runTest {
            forwardHistory.value = listOf(toUsd(index = 0, at = 100, AtomicSwapOutcome.Paid))
            reverseHistory.value = listOf(toZec(index = 1, ReversePhase.COMPLETE, acceptedAt = 300))
            val send = PrivateUsdSendRecord(TX_HASH, TEST_USD, BigInteger.valueOf(1_000_000), TO, sentAt = 200)
            sends.value = PrivateUsdSendHistory(listOf(send))
            start()

            assertEquals(
                listOf(
                    R.string.private_usd_activity_converted_zec,
                    R.string.private_usd_activity_sent,
                    R.string.private_usd_activity_converted,
                ),
                vm.state.value.activity
                    .map { (it.title as StringResource.ByResource).resource },
            )
        }

    @Test
    fun `a conversion into private USD under way shows as a banner that opens its progress`() =
        runTest {
            forward.value = AtomicSwapState(record = toUsd(index = 0, at = 10, outcome = null))
            start()

            val banner = checkNotNull(vm.state.value.conversion)
            banner.onClick()
            vm.state.value.convertButton
                .onClick()

            assertEquals(stringRes(R.string.private_usd_banner_title), banner.title)
            verify(exactly = 2) { navigation.forward(PrivateUsdProgressArgs) }
            assertFalse(vm.state.value.isEmpty)
        }

    @Test
    fun `a conversion back to ZEC waiting on the user shows as a banner that needs attention`() =
        runTest {
            reverse.value = ReverseSwapState(toZec(index = 1, ReversePhase.AWAITING_READY))
            start()

            val banner = checkNotNull(vm.state.value.conversion)
            banner.onClick()

            assertTrue(banner.isAttention)
            assertEquals(stringRes(R.string.private_usd_banner_attention_title), banner.title)
            verify { navigation.forward(PrivateUsdReverseArgs) }
        }

    @Test
    fun `with nothing under way, converting starts a new conversion`() =
        runTest {
            start()

            vm.state.value.convertButton
                .onClick()

            verify { navigation.forward(PrivateUsdConvertArgs) }
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val swaps =
            mockk<AtomicSwapRepository>(relaxed = true) {
                every { deployment } returns AtomicSwapTestnet.deployment
                every { state } returns forward
                every { history } returns forwardHistory
            }
        val reverseSwaps =
            mockk<ReverseSwapRepository> {
                every { state } returns reverse
                every { history } returns reverseHistory
            }
        vm =
            PrivateUsdVM(
                balanceRepository =
                    mockk<PrivateUsdBalanceRepository>(relaxed = true) {
                        every { state } returns balance
                        every { observe() } returns balance
                    },
                atomicSwapRepository = swaps,
                senders = mockk<PrivateUsdSenders> { every { current } returns mockk() },
                observeConversion = ObservePrivateUsdConversionUseCase(swaps, reverseSwaps),
                observeActivity =
                    ObservePrivateUsdActivityUseCase(
                        swaps,
                        reverseSwaps,
                        mockk<PrivateUsdSendLog> { every { observe } returns sends },
                    ),
                observeLocalCurrency =
                    mockk<ObserveLocalCurrencyUseCase> {
                        every { this@mockk.invoke() } returns flowOf(LocalCurrency.DOLLAR)
                    },
                activityMapper = PrivateUsdActivityMapper(),
                navigateBackToPay = mockk(relaxed = true),
                navigationRouter = navigation,
                spendGuard =
                    mockk<PrivateUsdSpendGuard> {
                        every { state } returns MutableStateFlow(PrivateUsdSpendStatus.AVAILABLE)
                    },
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private fun funds(vararg assets: PrivateUsdAsset) =
        PrivateUsdBalanceState(balances = PrivateUsdBalances(assets.toList()))

    private fun dollars(amount: String): BigInteger =
        BigDecimal(amount).movePointRight(token.decimals).toBigIntegerExact()

    private companion object {
        val TX_HASH = TxHash.fromHex("0x" + "ab".repeat(32))
        val TO =
            RailgunDestination.Private(
                RailgunAddress(
                    "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                        "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
                )
            )
    }
}
