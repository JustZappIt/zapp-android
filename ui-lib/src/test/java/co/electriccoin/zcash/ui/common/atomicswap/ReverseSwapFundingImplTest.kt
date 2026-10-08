// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.RelayerTerms
import xyz.justzappit.offramp.atomicswap.ReverseFundingRequest
import xyz.justzappit.offramp.atomicswap.ReverseFundingTerms
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.Sent
import xyz.justzappit.offramp.atomicswap.SwapRelayer
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.railgun.RailgunReverseCost
import xyz.justzappit.railgun.RailgunReverseRequest
import xyz.justzappit.railgun.RailgunReverseTransaction
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReverseSwapFundingImplTest {
    @Test
    fun `new funding is proved for the pinned adapter and submitted without a phone gas wallet`() =
        runTest {
            val wallet = mockk<RailgunWalletRepository>()
            val relayer = mockk<SwapRelayer>()
            val record = record()
            val proved = RailgunReverseTransaction(Sepolia.RELAY_ADAPT, DATA, BigInteger.ZERO, COST)
            val preparation = slot<RailgunReverseRequest>()
            coEvery { relayer.terms() } returns TERMS
            coEvery { wallet.prepareReverse(capture(preparation)) } returns proved
            val submission = slot<ReverseFundingRequest>()
            coEvery { relayer.fundReverse(capture(submission)) } returns Sent(listOf(HASH))
            val funding = ReverseSwapFundingImpl(wallet, DEPLOYMENT, relayer)

            val kept = funding.prepare(record, ByteArray(65))
            assertNull(kept.txId)
            assertEquals(Sepolia.RELAY_ADAPT, preparation.captured.relayAdapt)
            assertEquals(
                listOf(DEPLOYMENT.token, DEPLOYMENT.contract, DEPLOYMENT.token),
                preparation.captured.calls.map { it.to }
            )
            assertEquals(FEE.micros, preparation.captured.fee)
            assertEquals(FEE, kept.cost.broadcasterFee)
            assertEquals(HASH, funding.submit(kept))
            assertEquals(kept.request, submission.captured)
            assertEquals(record.swapId, submission.captured.swapId)
            assertEquals("0", submission.captured.value)
            coVerify(exactly = 0) { wallet.prove(any(), any()) }
        }

    @Test
    fun `absent or mismatched sponsorship blocks proving rather than switching gas routes`() =
        runTest {
            val wallet = mockk<RailgunWalletRepository>()
            val relayer = mockk<SwapRelayer>()
            val funding = ReverseSwapFundingImpl(wallet, DEPLOYMENT, relayer)
            coEvery { relayer.terms() } returns TERMS.copy(reverseFunding = null)
            val unsupported = assertFailsWith<AtomicSwapBlockedException> { funding.cost(Usdc6.ofMicros(1_000_000)) }
            assertEquals(AtomicSwapBlock.FUNDING_UNAVAILABLE, unsupported.reason)
            val mismatched = TERMS.reverseFunding?.copy(relayAdapt = DEPLOYMENT.token)
            coEvery { relayer.terms() } returns TERMS.copy(reverseFunding = mismatched)
            assertFailsWith<AtomicSwapBlockedException> {
                funding.prepare(record(), ByteArray(65))
            }
            coVerify(exactly = 0) { wallet.prepareReverse(any()) }
            coVerify(exactly = 0) { wallet.reverseCost(any()) }
        }

    private fun record(): ReverseSwapRecord {
        val saved = toZec(0, ReversePhase.AWAITING_FUNDING)
        val terms =
            saved.quote.terms.copy(
                chainId = DEPLOYMENT.chainId,
                contract = DEPLOYMENT.contract,
                token = DEPLOYMENT.token,
                maker = DEPLOYMENT.maker,
            )
        return saved.copy(deployment = DEPLOYMENT, quote = saved.quote.copy(terms = terms))
    }

    private companion object {
        val DEPLOYMENT = AtomicSwapTestnet.deployment.swap
        val FEE = Usdc6.ofMicros(250_000)
        val TERMS =
            RelayerTerms(
                DEPLOYMENT.relayer,
                DEPLOYMENT.chainId,
                DEPLOYMENT.contract,
                Usdc6.ofMicros(100_000),
                ReverseFundingTerms(
                    Sepolia.RELAY_ADAPT,
                    DEPLOYMENT.token,
                    DEPLOYMENT.maker,
                    4_000_000,
                    "20000000000",
                    65_536,
                    FEE,
                ),
            )
        val COST = RailgunReverseCost(BigInteger("1002506"), BigInteger("2506"))
        val HASH = TxHash.fromHex("0x" + "08".repeat(32))
        const val DATA = "0x12345678"
    }
}
