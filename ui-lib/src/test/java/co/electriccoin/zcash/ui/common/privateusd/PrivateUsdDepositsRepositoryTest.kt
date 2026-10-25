// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.configuration.model.map.StringConfiguration
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.deposits.DepositLanding
import xyz.justzappit.offramp.deposits.DepositPass
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositScan
import xyz.justzappit.offramp.deposits.DepositState
import xyz.justzappit.offramp.deposits.DepositTerms
import xyz.justzappit.offramp.deposits.DepositTermsRead
import xyz.justzappit.offramp.deposits.Depositor
import xyz.justzappit.offramp.deposits.ScannedAddress
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.Bps
import xyz.justzappit.railgun.RailgunAddress
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdDepositsRepositoryTest {
    private val depositor = mockk<Depositor>()
    private val store = PrivateUsdDepositStore(InMemoryPreferenceProvider().encrypted())
    private val balance = mockk<PrivateUsdBalanceRepository>(relaxed = true)
    private val railgun = MutableStateFlow(RailgunWalletState(RailgunWalletState.Phase.IDLE, null))
    private var isShieldedUsdOn = true
    private var next: DepositPass = pass()
    private var passes = 0

    // As the provider's: the current wallet first, then each that replaces it.
    private val walletChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private var hasWallet = true

    init {
        coEvery { depositor.pass(any()) } answers {
            passes += 1
            next
        }
        coEvery { depositor.address(any()) } answers { depositAddress(firstArg()) }
    }

    @Test
    fun `nothing is looked at until Zapp is open and unlocked, which brings a pass at once`() =
        runTest {
            val repository = repository()
            repository.refresh()
            runCurrent()
            assertEquals(0, passes)

            repository.onAppUnlocked(true)
            runCurrent()

            assertEquals(1, passes)
            coVerify { depositor.pass(signNew = true) }
        }

    @Test
    fun `passes come when asked, and as the last one said, only while unlocked`() =
        runTest {
            next = pass(nextPoll = 10.seconds)
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()
            assertEquals(1, passes)

            advanceTimeBy(10_001)
            assertEquals(2, passes)
            next = pass()
            repository.refresh()
            runCurrent()
            assertEquals(3, passes)

            repository.onAppUnlocked(false)
            repository.refresh()
            advanceTimeBy(60_000)
            assertEquals(3, passes)
            // Back in the foreground, unlocked: one pass, for both asks.
            repository.onAppUnlocked(true)
            runCurrent()
            assertEquals(4, passes)
        }

    @Test
    fun `each sync of the balance brings a pass`() =
        runTest {
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()

            railgun.value = railgun.value.copy(sync = mockk(relaxed = true))
            runCurrent()

            assertEquals(2, passes)
        }

    @Test
    fun `new deposits are signed only while Shielded USD is switched on`() =
        runTest {
            isShieldedUsdOn = false
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()

            coVerify { depositor.pass(signNew = false) }
        }

    @Test
    fun `the state shows the address to send to, the live terms with their example, and each line`() =
        runTest {
            val repository = repository()
            assertEquals(PrivateUsdDeposits(TOKEN, address = null), repository.state.value)

            next = pass(scan = DepositScan(NOW, showing = 3, used = listOf(ScannedAddress(1, Usdc6.ofMicros(500_000)))))
            repository.onAppUnlocked(true)
            runCurrent()

            val state = assertNotNull(repository.state.value)
            assertEquals(depositAddress(3), state.address)
            assertFalse(state.isAddressFailed)
            assertEquals(BigInteger.valueOf(2_000_000), state.terms?.minValue)
            assertEquals(Bps(10), state.terms?.feeBps)
            // $20 less $0.12, then less Railgun's 0.25% of the rest.
            assertEquals(BigInteger.valueOf(19_830_300), state.terms?.example?.arrives)
            assertFalse(state.isTermsFailed)
            assertEquals(
                listOf(
                    PrivateUsdDepositLine(
                        depositAddress(1),
                        PrivateUsdDepositPhase.UNDER_MINIMUM,
                        BigInteger.valueOf(500_000),
                    ),
                ),
                state.lines,
            )
        }

    @Test
    fun `terms that take no deposits here hide them, and terms that don't read keep the last that did`() =
        runTest {
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()
            assertNotNull(repository.state.value?.terms)

            next = pass(terms = null)
            repository.refresh()
            runCurrent()
            assertNotNull(repository.state.value?.terms)
            assertFalse(repository.state.value?.isTermsFailed == true)

            next = pass(terms = DepositTermsRead.Unavailable)
            repository.refresh()
            runCurrent()
            assertNull(repository.state.value)

            next = pass(terms = DepositTermsRead.OtherDeployment)
            repository.refresh()
            runCurrent()
            assertNull(repository.state.value)
        }

    @Test
    fun `terms that never read say so, and a chain never read leaves the address unknown`() =
        runTest {
            next = pass(terms = null, scan = null)
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()

            val state = assertNotNull(repository.state.value)
            assertTrue(state.isTermsFailed)
            assertNull(state.terms)
            assertTrue(state.isAddressFailed)
            assertNull(state.address)
        }

    @Test
    fun `a deposit that lands brings a sync, so the balance shows it arriving`() =
        runTest {
            val sent = depositRecord(state = DepositState.SENT, transactions = listOf(depositTx(1)))
            store.keep(listOf(sent))
            val landing = DepositLanding(depositTx(1), Usdc6.ofMicros(4_895_000), Usdc6.ofMicros(105_000), NOW)
            val landed = sent.copy(state = DepositState.LANDED, landing = landing, endedAt = NOW)
            next = pass(records = listOf(landed))
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()
            verify(exactly = 1) { balance.refresh(Duration.ZERO) }

            // Landed before the pass, it brings none.
            store.keep(listOf(landed))
            repository.refresh()
            runCurrent()
            verify(exactly = 1) { balance.refresh(any()) }
        }

    @Test
    fun `a pass that can't run leaves the address unknown, and the next ask runs one`() =
        runTest {
            coEvery { depositor.pass(any()) } throws IllegalStateException("no wallet")
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()
            assertTrue(repository.state.value?.isAddressFailed == true)

            coEvery { depositor.pass(any()) } returns pass()
            repository.refresh()
            runCurrent()
            assertEquals(depositAddress(0), repository.state.value?.address)
        }

    @Test
    fun `a reset stops the passes, forgets what was shown, and starts over for the next wallet`() =
        runTest {
            val repository = repository()
            repository.onAppUnlocked(true)
            runCurrent()
            assertNotNull(repository.state.value?.address)

            repository.reset()
            assertEquals(PrivateUsdDeposits(TOKEN, address = null), repository.state.value)
            // The wipe goes on with no wallet: nothing reads, posts or writes back the wiped wallet's deposits.
            hasWallet = false
            walletChanges.emit(Unit)
            repository.refresh()
            runCurrent()
            assertEquals(1, passes)

            hasWallet = true
            walletChanges.emit(Unit)
            runCurrent()
            assertEquals(2, passes)
        }

    @Test
    fun `rescues and the addresses withdrawals refuse are the depositor's`() =
        runTest {
            coEvery { depositor.rescue(2) } returns depositRecord(index = 2)
            coEvery { depositor.ownAddresses() } returns setOf(depositAddress(0))
            val repository = repository()

            repository.rescue(2)

            coVerify { depositor.rescue(2) }
            assertEquals(setOf(depositAddress(0)), repository.ownAddresses())
        }

    private fun TestScope.repository() =
        PrivateUsdDepositsRepositoryImpl(
            deployment = AtomicSwapTestnet.deployment,
            depositor = depositor,
            store = store,
            railgunWalletRepository = mockk<RailgunWalletRepository> { every { state } returns railgun },
            balanceRepository = lazy { balance },
            configurationRepository =
                mockk<ConfigurationRepository> {
                    every { configurationFlow } returns
                        MutableStateFlow(
                            StringConfiguration(
                                persistentMapOf("is_private_usd_available" to isShieldedUsdOn.toString()),
                                updatedAt = null,
                            ),
                        )
                },
            railgunMnemonicProvider =
                mockk<RailgunMnemonicProvider> {
                    every { walletChanges } returns this@PrivateUsdDepositsRepositoryTest.walletChanges
                    coEvery { address() } answers { if (hasWallet) WALLET else error("no wallet") }
                },
            scope = backgroundScope,
            clock =
                object : Clock {
                    override fun now() = Instant.fromEpochSeconds(NOW)
                },
        )

    private companion object {
        const val NOW = 1_792_800_000L
        val WALLET =
            RailgunAddress(
                "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                    "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
            )
        val TOKEN = AtomicSwapTestnet.deployment.privateUsdToken
        val TERMS =
            DepositTerms(
                relayer = Address.parse("0xedc4c88a3f6ae8130a2c18babbabd8100cc1a446"),
                fee = BigInteger.valueOf(100_000),
                feeBps = 10,
                feeExpiresAt = null,
                minValue = BigInteger.valueOf(2_000_000),
                maxValidity = 86_400,
            )

        fun pass(
            terms: DepositTermsRead? = DepositTermsRead.Pinned(TERMS),
            scan: DepositScan? = DepositScan(NOW, showing = 0),
            records: List<DepositRecord> = emptyList(),
            nextPoll: Duration? = null,
        ) = DepositPass(terms, scan, scan != null, records, nextPoll, isFeeOverCap = false)
    }
}
