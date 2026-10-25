// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicSwapSettlementTest : AtomicSwapDriverFixtures() {
    @Test
    fun aPauseBeforeTheShareGoesNeverRevealsInsideTheMarginOrAfterT1() =
        runTest {
            for (remaining in listOf(CLAIM_MARGIN_SECONDS, 60L, 0L, -1L)) {
                val h = Harness()
                val record = h.depositedRecord()
                h.chain.stage = SwapStage.READY
                h.keys.beforeClaimSecret = { h.at(h.chain.t1 - remaining) }

                val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

                assertEquals(AtomicSwapBlock.CLAIM_WINDOW_SHUT, blocked.reason, "$remaining s before t1")
                assertTrue("/v1/claim" !in h.paths)
                assertNull(h.store.record?.outcome)
            }
        }

    @Test
    fun aHeadThatFallsBehindBeforeTheShareGoesNeverAuthorizesTheReveal() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.keys.beforeClaimSecret = { h.clock += 4 * 60 }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

            assertEquals(AtomicSwapBlock.CHAIN_LAGGING, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aClaimThatLandedMeanwhileKeepsTheSecretPrivate() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            // The relayer sent its own claim for the swap while the share was being derived.
            h.keys.beforeClaimSecret = { h.chain.stage = SwapStage.CLAIMED }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

            assertEquals(AtomicSwapBlock.CLAIM_WINDOW_SHUT, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aChangedPayoutCommitmentBeforeTheRevealKeepsTheSecretPrivate() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.keys.beforeClaimSecret = { h.chain.payoutNote = NoteCommitment.of(ByteArray(32) { 6 }) }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

            assertEquals(AtomicSwapBlock.MISMATCH, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun anUnconfirmedOpenIsNeitherFundedNorAbandonedAfterTheQuoteExpires() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.confirmed = { null }
            h.clock = record.quote.expiresAt + 600

            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.OPENING), h.driver.advance(record))
            assertTrue(h.zcash.deposits.isEmpty())
            assertNull(h.store.record?.outcome)
        }

    @Test
    fun aReadyTheConfirmedReadDoesNotShowYetIsClaimedOnTheNewestBlockOnceDeepEnough() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            val open = h.chain.swap(record.swapId)
            h.chain.confirmed = { if (h.chain.stage == SwapStage.READY) open else h.chain.current() }
            h.chain.stage = SwapStage.READY
            h.chain.readyBlock = HEAD - 1

            assertEquals(waiting(AtomicSwapWait.READY_DEEPENING), h.driver.advance(record))
            assertEquals(1, h.zcash.submitted().size, "a deposit is never sent again into a swap made ready")
            assertTrue("/v1/claim" !in h.paths)

            h.chain.readyBlock = HEAD - READY_DEPTH
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue("/v1/claim" in h.paths)
        }

    @Test
    fun anOrphanedPayoutIsRetriedUntilItHasItsConfirmations() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED
            val withoutPayout = h.chain.swap(record.swapId)
            h.chain.confirmed = { withoutPayout }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.CHAIN_LAGGING, blocked.reason)
            assertTrue(h.chain.paidOut, "the payout is at the tip, but not confirmed")
            assertNull(h.store.record?.outcome)

            // A reorg removes the first payout. Recovery is still active and sends it again.
            h.chain.paidOut = false
            h.chain.confirmed = null

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(h.store.record!!))
            assertEquals(2, h.paths.count { it == "/v1/payout" })
            assertEquals(record.deposit, h.store.record?.deposit)
        }

    @Test
    fun aPaidFlagWithoutAConfirmedEventDoesNotCompleteTheConversion() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED
            h.chain.paidOut = true
            h.chain.payoutVisible = false

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.CHAIN_LAGGING, blocked.reason)
            assertNull(h.store.record?.outcome)

            h.chain.payoutVisible = true
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(h.store.record!!))
        }

    @Test
    fun completionRecordsTheConfirmedPayoutHashAndFeeInsteadOfTheQuote() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED
            h.chain.paidOut = true
            h.chain.payoutHash = TxHash.fromHex(hash(9))
            h.chain.payoutFee = Usdc6.ofMicros(10_000)

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertEquals(h.chain.payoutHash, h.store.record?.payoutTx)
            assertEquals(Usdc6.ofMicros(987_525), h.store.record?.receives)
            assertEquals(record.relayerFee, h.store.record?.relayerFee, "the offer's fee is kept")
            assertEquals(listOf(Usdc6.ofMicros(20_000)), h.keys.signedFees, "nothing but the hand-over's payout")
        }

    @Test
    fun aLowerFeeCanFinishASplitClaimAndUpdatesTheReceivedAmount() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.claimPaysOut = false
            h.feeAfterClaim = "10000"

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertEquals(listOf(Usdc6.ofMicros(20_000), Usdc6.ofMicros(10_000)), h.keys.signedFees)
            assertEquals(Usdc6.ofMicros(987_525), h.store.record?.receives)
        }
}
