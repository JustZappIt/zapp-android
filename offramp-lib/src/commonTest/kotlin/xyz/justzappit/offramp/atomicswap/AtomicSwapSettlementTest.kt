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
    fun aDelayedLockResponseNeverRevealsInsideTheMarginOrAfterExpiry() =
        runTest {
            for (remaining in listOf(60L, 0L, -1L)) {
                val h = Harness()
                val record = h.depositedRecord()
                h.chain.stage = SwapStage.READY
                h.afterLock = {
                    h.chain.now = h.chain.claimLockUntil - remaining
                    h.clock = h.chain.now
                }

                val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

                assertEquals(AtomicSwapBlock.CLAIM_LOCK_LAPSING, blocked.reason)
                assertTrue("/v1/claim" !in h.paths)
                assertNull(h.store.record?.outcome)
            }
        }

    @Test
    fun aPauseDuringSecretDerivationCannotBypassTheLockMargin() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.keys.beforeClaimSecret = {
                h.chain.now += LOCK_SECONDS
                h.clock = h.chain.now
            }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

            assertEquals(AtomicSwapBlock.CLAIM_LOCK_LAPSING, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aHeadThatFallsBehindWhileWaitingNeverAuthorizesTheReveal() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.afterLock = { h.clock += 4 * 60 }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

            assertEquals(AtomicSwapBlock.CHAIN_LAGGING, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aLockRemovedBeforeTheRevealKeepsTheSecretPrivate() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.keys.beforeClaimSecret = { h.chain.claimLockUntil = 0 }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

            assertEquals(AtomicSwapBlock.CLAIM_LOCK_LAPSING, blocked.reason)
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
    fun anUnconfirmedClaimLockNeverReleasesTheSecret() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            val beforeLock = h.chain.swap(record.swapId)
            h.chain.confirmed = { beforeLock }

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }

            assertEquals(AtomicSwapBlock.CHAIN_LAGGING, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
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
            assertEquals(record.relayerFee, h.store.record?.relayerFee, "the reviewed cap is retained")
            assertTrue(h.keys.signedFees.isEmpty())
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
