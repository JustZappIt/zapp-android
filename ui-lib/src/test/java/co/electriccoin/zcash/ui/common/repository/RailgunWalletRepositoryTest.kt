// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalances
import xyz.justzappit.railgun.RailgunBroadcaster
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunFees
import xyz.justzappit.railgun.RailgunNullifiers
import xyz.justzappit.railgun.RailgunRelayRequest
import xyz.justzappit.railgun.RailgunRelayedProof
import xyz.justzappit.railgun.RailgunSession
import xyz.justzappit.railgun.RailgunTransfer
import xyz.justzappit.railgun.RailgunWallet
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RailgunWalletRepositoryTest {
    private val session = mockk<RailgunSession>(relaxed = true)
    private val wallet = mockk<RailgunWallet>(relaxed = true)
    private val walletChanges = MutableSharedFlow<Unit>(replay = 1)
    private var openSession: RailgunSession? = null

    init {
        every { session.address } returns ADDRESS
        every { session.fees } returns RailgunFees(25)
        coEvery { session.refresh() } returns RailgunBalances(emptyMap())
        coEvery { session.prove(any(), any(), any()) } returns PROOF
        every { wallet.events } returns MutableSharedFlow()
        every { wallet.session } answers { openSession }
        coEvery { wallet.open(any(), any(), any()) } answers {
            openSession = session
            session
        }
        walletChanges.tryEmit(Unit)
    }

    @Test
    fun `a caller that stops waiting leaves the engine to the page until it answers`() =
        runTest {
            val repository = repository()
            val answer = CompletableDeferred<RailgunBalances>()
            coEvery { session.refresh() } coAnswers { answer.await() }

            val caller = launch { repository.sync() }
            runCurrent()
            caller.cancel()
            val next = async { repository.prove(TRANSFER, BROADCASTER, FEE) }
            runCurrent()
            assertFalse(next.isCompleted)

            answer.complete(RailgunBalances(emptyMap()))
            assertEquals(PROOF, next.await())
        }

    @Test
    fun `the engine opens once per page, and again once its page is gone`() =
        runTest {
            val repository = repository()

            repository.sync()
            repository.fees()
            coVerify(exactly = 1) { wallet.open(any(), any(), any()) }

            openSession = null
            repository.sync()
            coVerify(exactly = 2) { wallet.open(any(), any(), any()) }
        }

    @Test
    fun `a transfer comes back proved, for its sender to keep before it goes out`() =
        runTest {
            assertEquals(PROOF, repository().prove(TRANSFER, BROADCASTER, FEE))
        }

    @Test
    fun `a failure while proving throws, and shows on the state`() =
        runTest {
            coEvery { session.prove(any(), any(), any()) } throws RailgunException.Failed("no spendable notes")

            val repository = repository()
            assertFailsWith<RailgunException.Failed> { repository.prove(TRANSFER, BROADCASTER, FEE) }
            assertEquals(RailgunWalletState.Phase.FAILED, repository.state.value.phase)
        }

    @Test
    fun `a reset wipes the engine's storage, and nothing runs until the wallet changes`() =
        runTest {
            val repository = repository()
            repository.sync()

            repository.reset()

            coVerify { wallet.wipe() }
            assertEquals(RailgunWalletState.Phase.IDLE, repository.state.value.phase)
            assertFailsWith<IllegalStateException> { repository.sync() }
            walletChanges.emit(Unit)
            runCurrent()
            repository.sync()
        }

    @Test
    fun `a mainnet build stays unavailable, whatever happens to its wallet`() =
        runTest {
            val repository = repository(ZcashNetwork.Mainnet)

            walletChanges.emit(Unit)
            repository.reset()
            runCurrent()

            assertEquals(RailgunWalletState.Phase.UNAVAILABLE, repository.state.value.phase)
            assertFailsWith<IllegalStateException> { repository.sync() }
            coVerify(exactly = 0) { wallet.open(any(), any(), any()) }
            coVerify(exactly = 0) { wallet.wipe() }
        }

    @Test
    fun `a sync names the wallet it ran for`() =
        runTest {
            val sync = repository().sync()

            assertEquals(ADDRESS, sync.address)
            assertTrue(sync.balances.byBucket.isEmpty())
        }

    private fun TestScope.repository(network: ZcashNetwork = ZcashNetwork.Testnet): RailgunWalletRepository {
        val keys = mockk<RailgunKeyProvider>()
        coEvery { keys.encryptionKey() } answers { ByteArray(KEY_BYTES) }
        val mnemonic = mockk<RailgunMnemonicProvider>()
        every { mnemonic.walletChanges } returns walletChanges
        coEvery { mnemonic.address() } returns ADDRESS
        coEvery { mnemonic.withMnemonic(any<suspend (CharArray) -> RailgunSession>()) } coAnswers {
            firstArg<suspend (CharArray) -> RailgunSession>()(CharArray(0))
        }
        val networks = mockk<ZcashNetworkProvider>()
        every { networks() } returns network
        return RailgunWalletRepositoryImpl(wallet, keys, mnemonic, networks, backgroundScope)
    }

    private companion object {
        const val KEY_BYTES = 32
        val ADDRESS =
            RailgunAddress(
                "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                    "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
            )
        val PROXY = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea")
        val PROOF =
            RailgunRelayedProof(
                RailgunRelayRequest(11_155_111, PROXY, "0x02", BigInteger.ZERO),
                listOf(RailgunNullifiers(0, listOf("0x" + "ab".repeat(32)))),
            )
        val BROADCASTER =
            RailgunBroadcaster(
                chainId = 11_155_111,
                railgunProxy = PROXY,
                railgunAddress = ADDRESS,
                feeToken = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"),
                minFee = BigInteger.valueOf(250_000),
                maxGasPrice = BigInteger.valueOf(20_000_000_000),
            )
        val FEE: BigInteger = BigInteger.valueOf(250_000)
        val TRANSFER =
            RailgunTransfer(
                RailgunDestination.Public(Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5")),
                Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"),
                BigInteger.ONE,
            )
    }
}
