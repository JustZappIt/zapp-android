// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployments
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapMainnet
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
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
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalanceBucket
import xyz.justzappit.railgun.RailgunBalances
import xyz.justzappit.railgun.RailgunBroadcaster
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunFees
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunNullifiers
import xyz.justzappit.railgun.RailgunReceivedSend
import xyz.justzappit.railgun.RailgunRefresh
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
import kotlin.test.assertNull
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
        coEvery { session.refresh() } returns REFRESHED
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
            val answer = CompletableDeferred<RailgunRefresh>()
            coEvery { session.refresh() } coAnswers { answer.await() }

            val caller = launch { repository.sync() }
            runCurrent()
            caller.cancel()
            val next = async { repository.prove(TRANSFER, BROADCASTER, FEE) }
            runCurrent()
            assertFalse(next.isCompleted)

            answer.complete(REFRESHED)
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
    fun `a mainnet build keeps its wallet on ethereum`() =
        runTest {
            assertEquals(RailgunNetwork.MAINNET, repository(AtomicSwapMainnet.deployment).state.value.network)
        }

    @Test
    fun `a build with no swap deployment stays unavailable, whatever happens to its wallet`() =
        runTest {
            val repository = repository(deployment = null)

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

    @Test
    fun `a sync carries the private sends received, or none when the page couldn't read them`() =
        runTest {
            val repository = repository()
            coEvery { session.refresh() } returns REFRESHED.copy(received = listOf(RECEIVED))
            assertEquals(listOf(RECEIVED), repository.sync().received)

            coEvery { session.refresh() } returns REFRESHED.copy(received = null)
            assertNull(repository.sync().received)
        }

    private fun TestScope.repository(
        deployment: AtomicSwapDeployment? = AtomicSwapTestnet.deployment
    ): RailgunWalletRepository {
        val keys = mockk<RailgunKeyProvider>()
        coEvery { keys.encryptionKey() } answers { ByteArray(KEY_BYTES) }
        val mnemonic = mockk<RailgunMnemonicProvider>()
        every { mnemonic.walletChanges } returns walletChanges
        coEvery { mnemonic.address() } returns ADDRESS
        coEvery { mnemonic.withMnemonic(any<suspend (CharArray) -> RailgunSession>()) } coAnswers {
            firstArg<suspend (CharArray) -> RailgunSession>()(CharArray(0))
        }
        val deployments = mockk<AtomicSwapDeployments> { every { current } returns deployment }
        return RailgunWalletRepositoryImpl(wallet, keys, mnemonic, deployments, backgroundScope)
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
        val REFRESHED = RailgunRefresh(RailgunBalances(emptyMap()), emptyList())
        val RECEIVED =
            RailgunReceivedSend(
                txid = TxHash.fromHex("0x" + "cd".repeat(32)),
                token = Address.parse("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"),
                amount = BigInteger.valueOf(5_000_000),
                bucket = RailgunBalanceBucket.SPENDABLE,
                blockTime = null,
                sender = null,
            )
        val TRANSFER =
            RailgunTransfer(
                RailgunDestination.Public(Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5")),
                Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"),
                BigInteger.ONE,
            )
    }
}
