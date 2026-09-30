// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.repository.RailgunSync
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalanceBucket
import xyz.justzappit.railgun.RailgunBalances
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunTokenAmount
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdBalanceRepositoryTest {
    private val preferences = InMemoryPreferenceProvider()
    private val railgunState =
        MutableStateFlow(RailgunWalletState(RailgunWalletState.Phase.IDLE, RailgunNetwork.SEPOLIA))
    private val railgun = mockk<RailgunWalletRepository>()
    private val history = MutableStateFlow<List<AtomicSwapRecord>>(emptyList())
    private val swaps = mockk<AtomicSwapRepository>()
    private val walletChanges = MutableSharedFlow<Unit>(replay = 1)
    private val mnemonic = mockk<RailgunMnemonicProvider>()
    private var syncs = 0

    init {
        every { railgun.state } returns railgunState
        every { swaps.state } returns MutableStateFlow(AtomicSwapState())
        every { swaps.history } returns history
        every { mnemonic.walletChanges } returns walletChanges
        coEvery { mnemonic.address() } returns WALLET
        walletChanges.tryEmit(Unit)
    }

    @Test
    fun `a sync that finds the same balance still makes it fresh`() =
        runTest {
            val repository = repository()
            syncsReturn(WALLET) { balances(ONE_DOLLAR) }
            backgroundScope.launch { repository.observe().collect() }

            advanceTimeBy(12.minutes)
            runCurrent()

            // One on opening, then one each time the last is five minutes old; never every half minute.
            assertEquals(3, syncs)
            assertEquals(now(), checkNotNull(repository.state.value.updatedAt) + 2.minutes)
        }

    @Test
    fun `loading the wallet again leaves a refresh under way showing`() =
        runTest {
            val repository = repository()
            val finish = CompletableDeferred<Unit>()
            syncsReturn(WALLET) {
                finish.await()
                balances(ONE_DOLLAR)
            }
            runCurrent()
            repository.refresh()
            runCurrent()

            walletChanges.emit(Unit)
            runCurrent()

            assertTrue(repository.state.value.isRefreshing)
            finish.complete(Unit)
            runCurrent()
            assertFalse(repository.state.value.isRefreshing)
        }

    @Test
    fun `refreshes run one at a time, and one that never ends times out as a failure`() =
        runTest {
            val repository = repository()
            val never = CompletableDeferred<Unit>()
            syncsReturn(WALLET) {
                never.await()
                balances(ONE_DOLLAR)
            }
            runCurrent()

            repository.refresh()
            repository.refresh()
            runCurrent()
            assertEquals(1, syncs)

            advanceTimeBy(5.minutes + 1.seconds)
            assertTrue(repository.state.value.refreshFailed)
            assertFalse(repository.state.value.isRefreshing)

            repository.refresh()
            runCurrent()
            assertFalse(repository.state.value.refreshFailed)
            assertTrue(repository.state.value.isRefreshing)
        }

    @Test
    fun `a sync of another wallet is never shown as this one's`() =
        runTest {
            val repository = repository()
            runCurrent()

            railgunState.update { it.copy(sync = RailgunSync(OTHER_WALLET, balances(ONE_DOLLAR), now())) }
            runCurrent()
            assertNull(repository.state.value.balances)
            assertNull(preferences.getString(CACHE_KEY))

            railgunState.update { it.copy(sync = RailgunSync(WALLET, balances(ONE_DOLLAR), now())) }
            runCurrent()
            assertEquals(0, BigDecimal.ONE.compareTo(checkNotNull(repository.state.value.balances).available))
            assertTrue(checkNotNull(preferences.getString(CACHE_KEY)).contains(WALLET.value))
        }

    @Test
    fun `after a failure, refreshes on their own wait, and one asked for does not`() =
        runTest {
            val repository = repository()
            coEvery { railgun.sync() } coAnswers {
                syncs += 1
                throw RailgunException.Failed("the RPC is down")
            }
            runCurrent()

            repository.refresh(maxAge = 5.minutes)
            runCurrent()
            repository.refresh(maxAge = 5.minutes)
            runCurrent()
            assertEquals(1, syncs)

            repository.refresh()
            runCurrent()
            assertEquals(2, syncs)

            advanceTimeBy(59.seconds)
            repository.refresh(maxAge = 5.minutes)
            runCurrent()
            assertEquals(2, syncs)
            advanceTimeBy(2.seconds)
            repository.refresh(maxAge = 5.minutes)
            runCurrent()
            assertEquals(3, syncs)
        }

    @Test
    fun `outside Private USD, nothing starts the engine until it is in use`() =
        runTest {
            val repository = repository()
            syncsReturn(WALLET) { balances(ONE_DOLLAR) }
            backgroundScope.launch { repository.observeIfUsed().collect() }

            advanceTimeBy(2.minutes)
            assertEquals(0, syncs)

            history.value = listOf(mockk(relaxed = true))
            advanceTimeBy(31.seconds)
            assertEquals(1, syncs)
        }

    @Test
    fun `the balance cached by an earlier build shows before any sync`() =
        runTest {
            preferences.putString(
                CACHE_KEY,
                """{"address":"${WALLET.value}","updatedAt":1700000000000,""" +
                    """"buckets":{"SPENDABLE":[{"token":"$TEST_USD","amount":"2500000"}],"SOMETHING_NEW":[]}}""",
            )

            val repository = repository()
            runCurrent()

            assertEquals(0, BigDecimal("2.5").compareTo(checkNotNull(repository.state.value.balances).available))
            assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_000), repository.state.value.updatedAt)
        }

    @Test
    fun `a cached balance it cannot read is dropped, not thrown`() =
        runTest {
            preferences.putString(
                CACHE_KEY,
                """{"address":"${WALLET.value}","updatedAt":1,""" +
                    """"buckets":{"SPENDABLE":[{"token":"$TEST_USD","amount":"2.5"}]}}""",
            )

            val repository = repository()
            runCurrent()

            assertNull(repository.state.value.balances)
        }

    private fun TestScope.repository() =
        PrivateUsdBalanceRepositoryImpl(
            railgunWalletRepository = railgun,
            atomicSwapRepository = swaps,
            railgunMnemonicProvider = mnemonic,
            sendLog = PrivateUsdSendLog(preferences.encrypted()),
            senders = mockk(relaxed = true),
            encryptedPreferenceProvider = preferences.encrypted(),
            scope = backgroundScope,
            clock = clock(),
        )

    private fun TestScope.clock() =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(START + testScheduler.currentTime)
        }

    private fun TestScope.now() = clock().now()

    private fun TestScope.syncsReturn(
        address: RailgunAddress,
        balances: suspend () -> RailgunBalances
    ) {
        coEvery { railgun.sync() } coAnswers {
            syncs += 1
            RailgunSync(address, balances(), now())
        }
    }

    private fun balances(amount: BigInteger) =
        RailgunBalances(
            mapOf(RailgunBalanceBucket.SPENDABLE to listOf(RailgunTokenAmount(Address.parse(TEST_USD), amount)))
        )

    private companion object {
        const val START = 1_800_000_000_000L
        const val TEST_USD = "0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"
        val ONE_DOLLAR: BigInteger = BigInteger.valueOf(1_000_000)
        val CACHE_KEY = PreferenceKey("private_usd_balances_v1")
        val WALLET =
            RailgunAddress(
                "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                    "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
            )
        val OTHER_WALLET =
            RailgunAddress(
                "0zk1qyt5x0c632363rrmd8psxws6n9tscm8gps277gzc0w3s5cg4mdpe9rv7j6fe3z53" +
                    "luahk4ksjwagt68fl2vguye054rxjyqzvhs9usq4rwrk09al6n0677pdrgn"
            )
    }
}
