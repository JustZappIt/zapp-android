// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapStoreImpl
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapStoreImpl
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunFees
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunSignedTransaction
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TestnetGasAccountSenderTest {
    private val wallet = mockk<RailgunWalletRepository>()
    private val transactions = mockk<GasAccountTransactions>()
    private val log = PrivateUsdSendLog(InMemoryPreferenceProvider().encrypted())

    @Test
    fun `a confirmed send is logged from its proof to its block`() =
        runTest {
            signsThen { GasAccountDelivery.CONFIRMED }

            assertEquals(PrivateUsdSendOutcome.Sent(TX_HASH), sender().send(WITHDRAWAL))

            val sent = log.observe.first()
            assertEquals(TX_HASH, sent.sends.single().txHash)
            assertTrue(sent.sends.single().confirmed)
            assertEquals(emptyList(), sent.pending)
        }

    @Test
    fun `a send that may have gone out keeps its hash, and what was signed to send it again`() =
        runTest {
            signsThen { error("the node took another transaction") }

            assertEquals(PrivateUsdSendOutcome.Unconfirmed(TX_HASH), sender().send(WITHDRAWAL))

            val kept =
                log.observe
                    .first()
                    .sends
                    .single()
            assertFalse(kept.confirmed)
            assertEquals(SIGNED.raw, kept.signed?.raw)
        }

    @Test
    fun `a send not in a block yet stays unconfirmed`() =
        runTest {
            signsThen { GasAccountDelivery.UNCONFIRMED }

            assertEquals(PrivateUsdSendOutcome.Unconfirmed(TX_HASH), sender().send(WITHDRAWAL))
        }

    @Test
    fun `a failure before signing sent nothing and leaves nothing behind`() =
        runTest {
            coEvery { wallet.sign(any()) } throws RailgunException.Timeout("TRANSFER")

            assertEquals(PrivateUsdSendOutcome.NotSent, sender().send(WITHDRAWAL))

            assertEquals(PrivateUsdSendHistory(), log.observe.first())
        }

    @Test
    fun `a refused or reverted send moved nothing and is forgotten`() =
        runTest {
            signsThen { GasAccountDelivery.FAILED }

            assertEquals(PrivateUsdSendOutcome.NotSent, sender().send(WITHDRAWAL))

            assertEquals(PrivateUsdSendHistory(), log.observe.first())
        }

    @Test
    fun `a caller that stops waiting stops neither the send nor its log`() =
        runTest {
            val mined = CompletableDeferred<Unit>()
            signsThen {
                mined.await()
                GasAccountDelivery.CONFIRMED
            }
            val sender = sender()

            val caller = launch { sender.send(WITHDRAWAL) }
            runCurrent()
            caller.cancel()
            mined.complete(Unit)
            runCurrent()

            assertTrue(
                log.observe
                    .first()
                    .sends
                    .single()
                    .confirmed
            )
        }

    @Test
    fun `an unresolved send blocks another before proof generation even with a new sender`() =
        runTest {
            signsThen { GasAccountDelivery.UNCONFIRMED }
            val first = sender()
            assertEquals(PrivateUsdSendOutcome.Unconfirmed(TX_HASH), first.send(WITHDRAWAL))

            assertEquals(PrivateUsdSendOutcome.Busy, sender().send(WITHDRAWAL))
            coVerify(exactly = 1) { wallet.sign(any()) }
        }

    @Test
    fun `two callers cannot generate overlapping proofs`() =
        runTest {
            val complete = CompletableDeferred<Unit>()
            signsThen {
                complete.await()
                GasAccountDelivery.CONFIRMED
            }
            val sender = sender()
            val first = launch { sender.send(WITHDRAWAL) }
            runCurrent()

            assertEquals(PrivateUsdSendOutcome.Busy, sender.send(WITHDRAWAL))
            coVerify(exactly = 1) { wallet.sign(any()) }
            complete.complete(Unit)
            first.join()
        }

    @Test
    fun `only a withdrawal pays railgun's unshield fee`() =
        runTest {
            coEvery { wallet.fees() } returns RailgunFees(shieldBasisPoints = 25, unshieldBasisPoints = 25)

            assertEquals(PrivateUsdSendCost(BigInteger.valueOf(2_500), 25), sender().cost(WITHDRAWAL))
            assertEquals(PrivateUsdSendCost(BigInteger.ZERO, 0), sender().cost(WITHDRAWAL.copy(to = PRIVATE)))
        }

    private fun TestScope.sender(): TestnetGasAccountSender {
        val preferences = InMemoryPreferenceProvider().encrypted()
        val guard =
            PrivateUsdSpendGuard(
                log,
                AtomicSwapStoreImpl(preferences),
                ReverseSwapStoreImpl(preferences),
                backgroundScope,
            )
        return TestnetGasAccountSender(wallet, transactions, log, backgroundScope, CLOCK, guard)
    }

    private fun signsThen(delivery: suspend () -> GasAccountDelivery) {
        coEvery { wallet.sign(any()) } returns SIGNED
        coEvery { wallet.sync() } throws RailgunException.Disconnected("not in this test")
        coEvery { transactions.deliver(SIGNED.raw, TX_HASH) } coAnswers { delivery() }
    }

    private companion object {
        val TX_HASH = TxHash.fromHex("0x" + "cd".repeat(32))
        val SIGNED =
            RailgunSignedTransaction(
                raw = "0x02",
                txHash = TX_HASH,
                from = Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5"),
                nonce = 7,
                proofDuration = null,
            )
        val CLOCK =
            object : Clock {
                override fun now() = Instant.fromEpochSeconds(1_000)
            }
        val PRIVATE =
            RailgunDestination.Private(
                RailgunAddress(
                    "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                        "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
                )
            )
        val WITHDRAWAL =
            PrivateUsdSendRequest(
                token = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar },
                amount = BigInteger.valueOf(1_000_000),
                to = RailgunDestination.Public(Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5")),
            )
    }
}
