// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A forward claim handed to the relayer with the deposit, for it to send as the claim's window opens. */
class AtomicSwapHoldTest : AtomicSwapDriverFixtures() {
    @Test
    fun theClaimIsHandedOverOnceTheDepositIsOutAndKeptAsPostedBeforeItGoes() =
        runTest {
            val h = Harness()
            h.holdStatus = HttpStatusCode.OK

            assertEquals(waiting(AtomicSwapWait.CLAIM_HELD), h.driver.advance(h.accepted()))
            assertEquals(listOf(txId("deposit1")), h.zcash.submitted())
            val posted = checkNotNull(h.keptAtHold.single())
            assertIs<SwapDeposit.Kept>(posted.deposit, "the deposit went out first")
            assertEquals(ClaimHold.POSTED, posted.claimHold)
            assertEquals(Usdc6.ofMicros(20_000), posted.payout?.fee, "its payout is kept before it goes")
            assertTrue(h.bodies.getValue(HOLD).contains("0x" + "09".repeat(32)), "the relayer gets z to send later")
            assertEquals(TICKET, h.tickets[HOLD])
            val held = checkNotNull(h.store.record)
            assertEquals(ClaimHold.HELD, held.claimHold)
            assertNull(held.claimPostedAt, "nothing is claimed yet")

            // Held, it's handed over once: the steps after wait for the window, and say the user may leave.
            assertEquals(waiting(AtomicSwapWait.CLAIM_HELD), h.driver.advance(held))
            assertEquals(1, h.paths.count { it == HOLD })
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aHeldClaimIsTheRelayersToSendForAMinuteOnceItsWindowOpens() =
        runTest {
            val h = Harness()
            h.holdStatus = HttpStatusCode.OK
            val record = h.depositedRecord()

            h.at(h.chain.t0)
            assertEquals(waiting(AtomicSwapWait.CLAIM_HELD), h.driver.advance(record))
            h.at(h.chain.t0 + 59)
            assertEquals(waiting(AtomicSwapWait.CLAIM_HELD), h.driver.advance(record))
            assertTrue("/v1/claim" !in h.paths, "the relayer sends a claim it holds as the window opens")

            h.at(h.chain.t0 + 60)
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue("/v1/claim" in h.paths, "with nothing landed a minute on, the app claims itself")
        }

    @Test
    fun beforeT0AHeldClaimWaitsForItsReadyFiveBlocksDeeperThanTheRelayerDoes() =
        runTest {
            val h = Harness()
            h.holdStatus = HttpStatusCode.OK
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.chain.readyBlock = HEAD - READY_DEPTH

            assertEquals(waiting(AtomicSwapWait.CLAIM_HELD), h.driver.advance(record))
            assertTrue("/v1/claim" !in h.paths, "a ready just deep enough is the relayer's to claim on")
            assertTrue(HEAD - READY_DEPTH - 5 in h.chain.readAt, "a minute back, five blocks lower")

            h.chain.head = HEAD + 5
            h.at(NOW + 60)
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue("/v1/claim" in h.paths)
        }

    @Test
    fun aRelayerThatRefusesTheClaimOrHasNoRouteForItLeavesItToTheAppAsBefore() =
        runTest {
            val refusals =
                listOf(
                    HttpStatusCode.NotFound to null,
                    HttpStatusCode.BadRequest to "rejected",
                    HttpStatusCode.BadRequest to "invalidRequest",
                )
            for ((status, code) in refusals) {
                val h = Harness()
                h.holdStatus = status
                h.holdCode = code
                val record = h.depositedRecord()

                assertEquals(ClaimHold.DECLINED, h.store.record?.claimHold)
                assertEquals(confirming(), h.driver.advance(record), "nothing is held, so nothing says so")
                assertEquals(1, h.paths.count { it == HOLD }, "declined, it isn't handed over again")
                h.at(h.chain.t0)
                assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
                assertTrue("/v1/claim" in h.paths, "the app claims the moment the window opens")
            }
        }

    @Test
    fun aClaimTheRelayerRejectsIsSignedAgainOnceAtTheHigherFeeItAsksNow() =
        runTest {
            val h = Harness()
            h.holdStatus = HttpStatusCode.OK
            h.feeExpiresAt = NOW + FEE_VALIDITY
            val record = h.accepted()
            h.relayerFee = "30000"
            h.leastFee = 30_000

            assertEquals(waiting(AtomicSwapWait.CLAIM_HELD), h.driver.advance(record))
            assertEquals(listOf(Usdc6.ofMicros(20_000), Usdc6.ofMicros(30_000)), h.keys.signedFees)
            assertEquals(2, h.paths.count { it == HOLD })
            val held = checkNotNull(h.store.record)
            assertEquals(Usdc6.ofMicros(30_000), held.payout?.fee)
            assertEquals(ClaimHold.HELD, held.claimHold)
        }

    @Test
    fun aHandOverWithNoAnswerGoesAgainAsKeptAndIsLeftTheRelayersMinuteMeanwhile() =
        runTest {
            val h = Harness()
            h.holdStatus = HttpStatusCode.ServiceUnavailable
            val record = h.depositedRecord()
            assertEquals(ClaimHold.POSTED, h.store.record?.claimHold)
            val first = h.bodies.getValue(HOLD)

            h.unreachable = HOLD
            h.at(h.chain.t0)
            assertEquals(confirming(), h.driver.advance(record), "it may be held: nothing says the user may leave")
            assertTrue("/v1/claim" !in h.paths, "nor does the app claim in the relayer's minute")
            assertEquals(ClaimHold.POSTED, h.store.record?.claimHold)

            h.unreachable = null
            h.holdStatus = HttpStatusCode.OK
            h.relayerFee = "25000"
            h.at(h.chain.t0 + 30)
            assertEquals(waiting(AtomicSwapWait.CLAIM_HELD), h.driver.advance(record))
            assertEquals(first, h.bodies.getValue(HOLD), "the same bytes, though the relayer's fee moved on")
            assertEquals(listOf(Usdc6.ofMicros(20_000)), h.keys.signedFees)
            assertEquals(3, h.paths.count { it == HOLD })
            assertEquals(ClaimHold.HELD, h.store.record?.claimHold)
        }

    @Test
    fun aClaimTheAppPostedItselfIsNeverHandedOverAfter() =
        runTest {
            val h = Harness()
            h.unreachable = HOLD
            val record = h.depositedRecord()
            h.unreachable = null
            h.holdStatus = HttpStatusCode.OK
            // As a build from before hand-overs kept a claim it posted.
            h.store.record = h.store.record?.copy(claimHold = null, claimPostedAt = NOW)

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals(1, h.paths.count { it == HOLD })
            assertNull(h.store.record?.claimHold)
        }
}
