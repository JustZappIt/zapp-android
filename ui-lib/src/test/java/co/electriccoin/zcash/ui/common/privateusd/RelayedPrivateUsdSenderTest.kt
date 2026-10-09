// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapStoreImpl
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapStoreImpl
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.RailgunBroadcast
import xyz.justzappit.offramp.atomicswap.RailgunSendRelayer
import xyz.justzappit.offramp.atomicswap.RailgunSendsTerms
import xyz.justzappit.offramp.atomicswap.RailgunTransactRequest
import xyz.justzappit.offramp.atomicswap.RelayerTerms
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.railgun.RailgunBroadcaster
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunFees
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunNullifiers
import xyz.justzappit.railgun.RailgunRelayRequest
import xyz.justzappit.railgun.RailgunRelayedProof
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RelayedPrivateUsdSenderTest {
    private val wallet = mockk<RailgunWalletRepository>()
    private val relayer = mockk<RailgunSendRelayer>()
    private val chain = mockk<PrivateUsdSendChain>()
    private val log = PrivateUsdSendLog(InMemoryPreferenceProvider().encrypted())
    private val posts = mutableListOf<RailgunTransactRequest>()
    private var now = 1_000L
    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochSeconds(now)
        }

    @Test
    fun `a send is kept before it goes to the relayer, and lands`() =
        runTest {
            answers {
                assertEquals(
                    PROOF.request,
                    log
                        .unconfirmed()
                        .single()
                        .relay
                        ?.request
                )
                RailgunBroadcast.Sent(TX_HASH)
            }
            coEvery { chain.status(TX_HASH) } returns TransactionStatus.CONFIRMED

            assertEquals(PrivateUsdSendOutcome.Sent(TX_HASH), sender().send(WITHDRAWAL, COST))

            assertEquals(listOf(RailgunTransactRequest(CHAIN, PROXY, DATA, "0")), posts)
            val sent =
                log.observe
                    .first()
                    .sends
                    .single()
            assertTrue(sent.confirmed)
            assertEquals(TX_HASH, sent.txHash)
            assertNull(sent.relay)
        }

    @Test
    fun `a refusal sent nothing, so the send is forgotten and the next one may go`() =
        runTest {
            answers { RailgunBroadcast.Refused("the transaction does not pay the relayer's fee") }
            val sender = sender()

            assertEquals(PrivateUsdSendOutcome.NotSent, sender.send(WITHDRAWAL, COST))
            assertEquals(PrivateUsdSendHistory(), log.observe.first())
            assertEquals(PrivateUsdSendOutcome.NotSent, sender.send(WITHDRAWAL, COST))
            coVerify(exactly = 2) { wallet.prove(any(), any(), any()) }
        }

    @Test
    fun `a post with no answer keeps blocking spends, and goes again as the very same bytes`() =
        runTest {
            answers { RailgunBroadcast.Retry("response lost") }
            val sender = sender()

            assertEquals(PrivateUsdSendOutcome.Unconfirmed(null), sender.send(WITHDRAWAL, COST))
            assertEquals(PrivateUsdSendOutcome.Busy, sender.send(WITHDRAWAL, COST))

            answers { RailgunBroadcast.Sent(TX_HASH) }
            coEvery { chain.status(TX_HASH) } returns TransactionStatus.CONFIRMED
            sender.reconcile()
            runCurrent()
            sender.reconcile()
            runCurrent()

            assertEquals(2, posts.size)
            assertEquals(posts.first(), posts.last())
            assertTrue(
                log.observe
                    .first()
                    .sends
                    .single()
                    .confirmed
            )
            coVerify(exactly = 1) { wallet.prove(any(), any(), any()) }
        }

    @Test
    fun `notes spent elsewhere settle from their nullifiers, asking again only after a while`() =
        runTest {
            answers { RailgunBroadcast.Spent(emptyList()) }
            coEvery { chain.spent(PROOF.spends) } returns PrivateUsdNullifiers.NONE
            val sender = sender()

            assertEquals(PrivateUsdSendOutcome.Unconfirmed(null), sender.send(WITHDRAWAL, COST))
            sender.reconcile()
            runCurrent()
            assertEquals(1, posts.size)

            now += 15.minutes.inWholeSeconds
            sender.reconcile()
            runCurrent()
            assertEquals(2, posts.size)

            coEvery { chain.spent(PROOF.spends) } returns PrivateUsdNullifiers.ALL
            sender.reconcile()
            runCurrent()
            val landed =
                log.observe
                    .first()
                    .sends
                    .single()
            assertTrue(landed.confirmed)
            assertNull(landed.txHash)
        }

    @Test
    fun `a send whose notes the relayer names its own transaction for lands with that transaction`() =
        runTest {
            answers { RailgunBroadcast.Spent(listOf(TX_HASH)) }
            coEvery { chain.status(TX_HASH) } returns TransactionStatus.CONFIRMED
            val sender = sender()

            assertEquals(PrivateUsdSendOutcome.Unconfirmed(TX_HASH), sender.send(WITHDRAWAL, COST))
            sender.reconcile()
            runCurrent()

            val landed =
                log.observe
                    .first()
                    .sends
                    .single()
            assertTrue(landed.confirmed)
            assertEquals(TX_HASH, landed.txHash)
            coVerify(exactly = 0) { chain.spent(any()) }
        }

    @Test
    fun `a send that reverted, or whose notes another proof took in part, moved nothing and is forgotten`() =
        runTest {
            answers { RailgunBroadcast.Sent(TX_HASH) }
            coEvery { chain.status(TX_HASH) } returns TransactionStatus.REVERTED
            val sender = sender()
            assertEquals(PrivateUsdSendOutcome.NotSent, sender.send(WITHDRAWAL, COST))
            assertEquals(PrivateUsdSendHistory(), log.observe.first())

            answers { RailgunBroadcast.Spent(emptyList()) }
            coEvery { chain.spent(PROOF.spends) } returns PrivateUsdNullifiers.SOME
            assertEquals(PrivateUsdSendOutcome.Unconfirmed(null), sender.send(WITHDRAWAL, COST))
            sender.reconcile()
            runCurrent()
            assertEquals(PrivateUsdSendHistory(), log.observe.first())
        }

    @Test
    fun `the network fee is the send's quote at the relayer's rate, on pinned terms with a readable rate only`() =
        runTest {
            coEvery { wallet.fees() } returns RailgunFees(unshieldBasisPoints = 25)
            coEvery { relayer.terms() } returns PRICED_TERMS
            val quoted = slot<RailgunBroadcaster>()
            coEvery { wallet.broadcasterFee(any(), capture(quoted)) } returns PRICED_FEE

            val withdrawal = PrivateUsdSendCost(BigInteger.valueOf(2_500), 25, PRICED_FEE, PIN.feeToken, EXPIRES_AT)
            assertEquals(withdrawal, sender().cost(WITHDRAWAL))
            assertEquals(BigInteger(RATE), quoted.captured.feePerUnitGas)
            assertEquals(BigInteger.valueOf(250_000), quoted.captured.minFee)

            coEvery { relayer.terms() } returns TERMS
            assertNull(sender().cost(WITHDRAWAL).networkFeeExpiresAt)
            assertNull(quoted.captured.feePerUnitGas)

            val unreadable =
                listOf("0", "-1", "0x10", "1.5", "", "\u0663").map { PRICED_SENDS.copy(feePerUnitGas = it) } +
                    PRICED_SENDS.copy(feeExpiresAt = null) +
                    PRICED_SENDS.copy(railgunAddress = OTHER_RAILGUN_ADDRESS)
            for (sends in unreadable + null) {
                coEvery { relayer.terms() } returns TERMS.copy(railgunSends = sends)
                assertFailsWith<PrivateUsdSendsUnavailableException> { sender().cost(WITHDRAWAL) }
            }
        }

    @Test
    fun `a higher fee is never paid unconfirmed, after an expired quote or a refusal`() =
        runTest {
            answers { RailgunBroadcast.Refused("the transaction does not pay the relayer's fee") }
            coEvery { relayer.terms() } returns PRICED_TERMS
            val confirmed = COST.copy(networkFee = PRICED_FEE, networkFeeExpiresAt = EXPIRES_AT)
            val higher = PRICED_FEE + BigInteger.ONE
            coEvery { wallet.broadcasterFee(any(), any()) } returns higher
            val sender = sender()

            now = EXPIRES_AT - 1
            val expired = sender.send(WITHDRAWAL, confirmed)
            assertEquals(PrivateUsdSendOutcome.Repriced(confirmed.copy(networkFee = higher)), expired)
            coVerify(exactly = 0) { wallet.prove(any(), any(), any()) }

            now = 1_000L
            val refused = sender.send(WITHDRAWAL, confirmed)
            assertEquals(PrivateUsdSendOutcome.Repriced(confirmed.copy(networkFee = higher)), refused)
            coVerify(exactly = 1) { wallet.prove(any(), any(), PRICED_FEE) }
            assertEquals(PrivateUsdSendHistory(), log.observe.first())
        }

    private fun TestScope.sender(): RelayedPrivateUsdSender {
        val preferences = InMemoryPreferenceProvider().encrypted()
        val guard =
            PrivateUsdSpendGuard(
                log,
                AtomicSwapStoreImpl(preferences),
                ReverseSwapStoreImpl(preferences),
                backgroundScope,
            )
        return RelayedPrivateUsdSender(
            railgunWalletRepository = wallet,
            relayer = PrivateUsdRelayer(relayer, PIN),
            chain = chain,
            pin = PIN,
            sendLog = log,
            scope = backgroundScope,
            clock = clock,
            spendGuard = guard,
        )
    }

    private fun answers(answer: suspend () -> RailgunBroadcast) {
        coEvery { relayer.terms() } returns TERMS
        coEvery { wallet.fees() } returns RailgunFees(unshieldBasisPoints = 25)
        coEvery { wallet.broadcasterFee(any(), any()) } returns COST.networkFee
        coEvery { wallet.prove(any(), any(), any()) } returns PROOF
        coEvery { wallet.sync() } throws RailgunException.Disconnected("not in this test")
        coEvery { relayer.transact(any()) } coAnswers {
            posts += firstArg<RailgunTransactRequest>()
            answer()
        }
    }

    private companion object {
        val PIN = checkNotNull(AtomicSwapTestnet.deployment.railgunSends)
        val CHAIN = PIN.chainId
        val PROXY = PIN.railgunProxy
        const val DATA = "0xd8ae136a"
        val TX_HASH = TxHash.fromHex("0x" + "cd".repeat(32))
        val PROOF =
            RailgunRelayedProof(
                RailgunRelayRequest(CHAIN.value, PROXY, DATA, BigInteger.ZERO),
                listOf(RailgunNullifiers(0, listOf("0x" + "ab".repeat(32)))),
            )
        const val OTHER_RAILGUN_ADDRESS =
            "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
        val SENDS =
            RailgunSendsTerms(
                railgunAddress = PIN.railgunAddress.value,
                railgunProxy = PROXY,
                token = PIN.feeToken,
                fee = Usdc6.ofMicros(250_000),
                maxGasLimit = 3_000_000,
                maxGasPriceWei = "20000000000",
                maxCalldataBytes = 65_536,
            )
        val TERMS =
            RelayerTerms(
                relayer = Address.parse("0xd9633572041886fa7584a2e12f36c8c7f1126412"),
                chainId = CHAIN,
                contract = Address.parse("0xD75Efc6a157CC0A95f66962DA86DDf35d9F2617c"),
                fee = Usdc6.ofMicros(20_000),
                railgunSends = SENDS,
            )
        const val RATE = "2726271000"
        const val EXPIRES_AT = 1_600L
        val PRICED_SENDS = SENDS.copy(feePerUnitGas = RATE, feeExpiresAt = EXPIRES_AT)
        val PRICED_TERMS = TERMS.copy(railgunSends = PRICED_SENDS)
        val PRICED_FEE: BigInteger = BigInteger.valueOf(3_598_677)
        val WITHDRAWAL =
            PrivateUsdSendRequest(
                token = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar },
                amount = BigInteger.valueOf(1_000_000),
                to = RailgunDestination.Public(Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5")),
            )
        val COST = PrivateUsdSendCost(BigInteger.valueOf(2_500), 25, BigInteger.valueOf(250_000), PIN.feeToken)
    }
}
