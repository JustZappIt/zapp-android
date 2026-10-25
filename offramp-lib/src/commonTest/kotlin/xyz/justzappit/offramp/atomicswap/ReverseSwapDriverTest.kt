// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReverseSwapDriverTest : ReverseSwapDriverFixtures() {
    @Test
    fun quotePreviewSurvivesRestartWithoutAcceptingOrFunding() =
        runTest {
            val h = Harness()
            h.driver.quote(AMOUNT)
            h.restart()
            h.driver.advance()
            assertEquals(ReversePhase.QUOTED, h.record.phase)
            assertFalse(h.record.underWay)
            assertNull(h.record.account)
            assertEquals(0, h.prepares)
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            h.driver.quote(AMOUNT)
            assertEquals(1, h.record.index)
            assertEquals(2, h.nextIndex)
        }

    @Test
    fun goingAheadRecordsWhenOnThisDevicesClock() =
        runTest {
            val h = Harness()
            h.driver.quote(AMOUNT)
            assertNull(h.record.acceptedAt, "a quote only previewed isn't activity")
            h.driver.goAhead(h.record.index)
            assertEquals(h.now, h.record.acceptedAt)
        }

    @Test
    fun twoAuthorizationsAndConfirmedReceive() =
        runTest {
            val h = Harness()
            h.prepared()
            assertEquals(ReversePhase.AWAITING_FUNDING, h.record.phase)
            assertTrue(h.record.underWay)
            assertEquals(0, h.prepares)
            h.driver.fund(0)
            assertEquals(1, h.prepares)
            assertNotNull(h.record.funding)
            h.funded()
            h.paidIn = DEPOSIT
            h.driver.advance()
            assertEquals(ReversePhase.AWAITING_READY, h.record.phase)
            assertEquals(0, h.readyCalls)
            h.driver.ready(0)
            assertEquals(1, h.readyCalls)
            h.escrow = h.swap(SwapStage.CLAIMED)
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertEquals(DEPOSIT - 10_000, h.record.receive?.receivedZat)
            assertEquals(1, h.receiveSubmits)
            val transaction = h.record.receive
            h.received = ZcashTransactionStatus.Mined(1)
            h.restart()
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertEquals(1L, h.record.receiveConfirmations)
            assertEquals(transaction, h.record.receive)
            assertEquals(1, h.receivePrepares)
            assertEquals(1, h.receiveSubmits)
            assertEquals(0, h.forgets)
            h.received = ZcashTransactionStatus.Mined(10)
            h.driver.advance()
            assertEquals(ReversePhase.COMPLETE, h.record.phase)
            assertEquals(1, h.forgets, "the joint account goes once the sweep is final")
        }

    @Test
    fun theAcceptsTicketIsKeptBeforeFundingAndTheRelayerRequestsCarryIt() =
        runTest {
            val h = Harness()
            h.prepared()
            assertEquals(TICKET, h.record.ticket, "kept once the maker accepts, before anything is funded")
            assertTrue(h.relayed.isEmpty())

            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.driver.advance()
            h.driver.ready(0)
            h.restart()
            h.driver.advance()

            assertEquals(TICKET, h.record.ticket)
            assertEquals(listOf("fund", "ready", "ready"), h.relayed.map { it.first })
            assertTrue(h.relayed.all { it.second == TICKET })
        }

    @Test
    fun eachRequestToGetTheDollarsBackCarriesTheTicket() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.driver.cancel(0)
            h.escrow = h.swap(SwapStage.REFUNDED)
            h.driver.advance()
            h.paidOut = true
            h.driver.advance()
            h.driver.rescue(0)

            assertEquals(ReversePhase.REFUNDED, h.record.phase)
            assertEquals(listOf("fund", "refund", "refundPayout", "rescue"), h.relayed.map { it.first })
            assertTrue(h.relayed.all { it.second == TICKET })
        }

    @Test
    fun aConversionWithoutATicketOfItsOwnGoesWithoutOne() =
        runTest {
            // An older maker's answer, and a ticket for another swap.
            for (ticket in listOf(null, "${hex(32, 9)}." + "3f".repeat(32))) {
                val h = Harness()
                h.acceptTicket = ticket
                h.prepared()
                assertNull(h.record.ticket)

                h.driver.fund(0)
                h.funded()
                h.driver.cancel(0)
                assertEquals(listOf<Pair<String, String?>>("fund" to null, "refund" to null), h.relayed)
            }
        }

    @Test
    fun partialAndUnconfirmedDepositsNeverEnableReady() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            for (amount in listOf(0L, 1L, DEPOSIT - 1)) {
                h.paidIn = amount
                h.driver.advance()
                assertEquals(ReversePhase.RECEIVING_ZEC, h.record.phase)
                assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            }
            assertNull(h.record.ready)
            assertEquals(0, h.readyCalls)
        }

    @Test
    fun readyWaitsForTheDepositsOwnConfirmationsAndNoMore() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.depositConfirmations = DEPLOYMENT.zcashConfirmations - 1
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING_ZEC, h.record.phase)
            val early = assertFailsWith<AtomicSwapBlockedException> { h.driver.ready(0) }
            assertEquals(AtomicSwapBlock.DEPOSIT_UNCONFIRMED, early.reason)

            h.depositConfirmations = DEPLOYMENT.zcashConfirmations
            h.driver.advance()
            assertEquals(ReversePhase.AWAITING_READY, h.record.phase)
            h.driver.ready(0)
            assertEquals(1, h.readySignatures)
        }

    @Test
    fun interruptedFundingReusesPersistedTransactionAfterRestart() =
        runTest {
            val h = Harness()
            h.prepared()
            h.interruptFunding = true
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            val saved = h.record.funding
            assertNotNull(saved)
            h.interruptFunding = false
            h.restart()
            h.driver.fund(0)
            h.driver.advance()
            assertEquals(1, h.prepares)
            assertTrue(h.submissions.all { it.request == saved.request })
        }

    @Test
    fun duplicateFundingAndReadyCallbacksDoNotCreateAnotherPaymentOrSignature() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.driver.ready(0)
            h.driver.ready(0)
            assertEquals(1, h.prepares)
            assertEquals(1, h.readySignatures)
        }

    @Test
    fun restartNeverAuthorizesReady() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.restart()
            repeat(3) { h.driver.advance() }
            assertEquals(ReversePhase.AWAITING_READY, h.record.phase)
            assertNull(h.record.ready)
            assertEquals(0, h.readySignatures)
        }

    @Test
    fun rolesAndEveryEscrowTermAreVerified() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            val good = h.state()
            ReverseSwapVerifier.verifyEscrow(h.record, good)
            val forwardRoles =
                OnChainSwap(
                    MAKER,
                    READY,
                    SwapStage.OPEN,
                    false,
                    USER,
                    REFUND,
                    TOKEN,
                    AMOUNT,
                    MAKER_SHARE,
                    USER_SHARE,
                    ByteArray(32),
                    EMPTY_NOTE,
                )
            assertFailsWith<IllegalStateException> { forwardRoles.state().verified(h.record.terms) }
            assertFailsWith<IllegalStateException> {
                ReverseSwapVerifier.verifyEscrow(h.record, good.copy(refundNote = EMPTY_NOTE))
            }
            assertFailsWith<IllegalStateException> {
                ReverseSwapVerifier.verifyEscrow(
                    h.record,
                    good.copy(fundingBlock = 0)
                )
            }
        }

    @Test
    fun rejectsMalformedOrMismatchedQuotesBeforeAcceptance() =
        runTest {
            val h = Harness()
            val quote = h.quote
            assertFailsWith<IllegalArgumentException> { Usdc6(BigInteger("1.25")) }
            for (malformed in listOf(
                quote.copy(terms = quote.terms.copy(amount = Usdc6.ofMicros(-1))),
                quote.copy(terms = quote.terms.copy(makerProof = "0x")),
                quote.copy(terms = quote.terms.copy(depositZat = 0)),
                quote.copy(readyDeadline = FUNDING),
            )) {
                h.quote = malformed
                assertFailsWith<IllegalArgumentException> { h.driver.quote(AMOUNT) }
                assertNull(h.saved)
            }
            for (bad in listOf(
                quote.copy(user = MAKER),
                quote.copy(refundNote = OTHER_NOTE),
                quote.copy(terms = quote.terms.copy(chainId = ChainId(1))),
                quote.copy(terms = quote.terms.copy(contract = TOKEN)),
                quote.copy(terms = quote.terms.copy(token = MAKER)),
                quote.copy(terms = quote.terms.copy(maker = USER)),
            )) {
                h.quote = bad
                assertFailsWith<IllegalStateException> { h.driver.quote(AMOUNT) }
                assertNull(h.saved)
            }
            h.quote = quote
            h.proofValid = false
            assertFailsWith<IllegalStateException> { h.driver.quote(AMOUNT) }
            assertNull(h.saved)
            assertTrue(h.nextIndex > 0)
        }

    @Test
    fun aConversionWhoseRefundTheRelayersFeeWouldTakeAllOfIsNeverQuoted() =
        runTest {
            val h = Harness()
            h.relayerFee = AMOUNT

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(AMOUNT) }
            assertEquals(AtomicSwapBlock.RELAYER_FEE, refused.reason)
            assertEquals(0, h.nextIndex)
            assertNull(h.saved)
        }

    @Test
    fun pendingSwapCannotMoveToAnotherDeployment() =
        runTest {
            val h = Harness()
            h.prepared()
            h.saved = h.record.copy(deployment = DEPLOYMENT.copy(contract = USER))
            assertFailsWith<IllegalStateException> { h.driver.advance() }
            assertEquals(0, h.prepares)
        }

    @Test
    fun fundingAndReadyRespectDeadlines() =
        runTest {
            val h = Harness()
            h.prepared()
            h.now = FUNDING
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            h.funded()
            h.paidIn = DEPOSIT
            h.now = READY - SIGNATURE_TTL_SECONDS
            assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            assertNull(h.record.ready)
        }

    @Test
    fun aCancelRefundsAnOpenEscrowNobodyMadeReadyAtOnceKeptAsPostedBeforeItGoes() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            assertEquals(0, h.payoutSignatures, "the payout is signed only when it goes out")
            h.driver.cancel(0)

            assertEquals(1, h.refundCalls)
            assertEquals(1, h.payoutSignatures)
            assertNotNull(h.record.payout)
            assertEquals(h.now, h.keptAtRefund.single()?.refundPostedAt, "kept before the share went")
            assertEquals(ReversePhase.REFUNDING, h.record.phase)
        }

    @Test
    fun aReadyEscrowIsRefundedOnlyFromT1() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.escrow = h.swap(SwapStage.READY)
            h.driver.cancel(0)
            assertEquals(ReversePhase.REFUND_WAIT, h.record.phase)
            h.now = REFUND - 1
            h.driver.advance()
            assertEquals(0, h.refundCalls, "until t1 a ready escrow is its maker's to claim")
            assertNull(h.record.refundPostedAt)
            h.now = REFUND
            h.driver.advance()
            assertEquals(1, h.refundCalls)
        }

    @Test
    fun anOpenEscrowIsNeverRefundedWhileTheReadyWeSignedCanStillLand() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.driver.advance()
            h.driver.ready(0)
            val deadline = checkNotNull(h.record.ready).deadline
            assertEquals(h.now + SIGNATURE_TTL_SECONDS, deadline)

            h.driver.cancel(0)
            for (now in listOf(h.now + 1, deadline)) {
                h.now = now
                h.driver.advance()
                assertEquals(0, h.refundCalls, "at $now the ready signed until $deadline may still land")
                assertNull(h.record.refundPostedAt)
                assertEquals(ReversePhase.REFUND_WAIT, h.record.phase)
            }
            h.now = deadline + 1
            h.driver.advance()
            assertEquals(1, h.refundCalls)
            assertEquals(deadline + 1, h.record.refundPostedAt)
        }

    @Test
    fun aReadyLandingFirstStopsTheRefundEvenAfterItsDeadlinePassedOnTheConfirmedRead() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.driver.advance()
            h.driver.ready(0)
            h.driver.cancel(0)
            h.now = checkNotNull(h.record.ready).deadline + 1
            // The confirmed read is behind: the newest block shows the ready landed.
            h.latest = { h.swap(SwapStage.READY) }

            h.driver.advance()

            assertEquals(0, h.refundCalls)
            assertEquals(ReversePhase.REFUND_WAIT, h.record.phase)
        }

    @Test
    fun aRefundTheRelayerHoldsBackGoesAgainAtTheNextStep() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.busyRefunds = 1

            assertFailsWith<AtomicSwapHttpException.Refused> { h.driver.cancel(0) }
            assertEquals(0, h.refundCalls)
            h.driver.advance()
            assertEquals(1, h.refundCalls)
            assertEquals(2, h.keptAtRefund.size)
            assertEquals(1, h.payoutSignatures, "the same request goes again")
        }

    @Test
    fun noReadyIsSignedOrSentAgainOnceARefundWasPosted() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            // Past t0 an open escrow is due its refund, and no ready is ever taken again.
            h.now = READY
            h.driver.advance()
            assertNotNull(h.record.refundPostedAt)

            assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            assertEquals(0, h.readySignatures)
            assertEquals(0, h.readyCalls)
        }

    @Test
    fun aQuoteLeavingTheContractLessThanItsLeastWindowIsNeverTaken() =
        runTest {
            val quote = Harness().quote
            for (short in listOf(
                quote.copy(fundingDeadline = READY - WINDOWS.min + 1),
                quote.copy(refundAfter = READY + WINDOWS.min - 1),
            )) {
                val h = Harness()
                h.quote = short
                val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(AMOUNT) }
                assertEquals(AtomicSwapBlock.MISMATCH, refused.reason)
                assertNull(h.saved)
            }
        }

    @Test
    fun aConversionOnARetiredContractIsNeverQuotedPaidOrRefundedAndOneNeverPaidEnds() =
        runTest {
            val locks = DEPLOYMENT.copy(windows = null)
            val live = Harness()
            live.prepared()
            live.driver.fund(0)
            val unpaid = live.record.copy(deployment = locks, funding = null, phase = ReversePhase.AWAITING_FUNDING)
            val retired = Harness(locks)
            retired.saved = unpaid

            val quoted = assertFailsWith<AtomicSwapBlockedException> { retired.driver.quote(AMOUNT) }
            assertEquals(AtomicSwapBlock.RETIRED_CONTRACT, quoted.reason)
            assertFailsWith<AtomicSwapBlockedException> { retired.driver.fund(0) }
            retired.driver.advance()
            assertEquals(ReversePhase.CANCELLED, retired.record.phase)
            assertEquals(1, retired.forgets)

            val funded = Harness(locks)
            funded.saved = unpaid.copy(phase = ReversePhase.CONFIRMING_ESCROW, funding = live.record.funding)
            funded.escrow = funded.swap(SwapStage.OPEN)
            val refused = assertFailsWith<AtomicSwapBlockedException> { funded.driver.advance() }
            assertEquals(AtomicSwapBlock.RETIRED_CONTRACT, refused.reason)
            assertFalse(funded.driver.canRescue(0))
            assertTrue(funded.relayed.isEmpty())
            assertEquals(0, funded.accepts)
        }

    @Test
    fun aRetiredConversionItsMakerClaimedComesHomeOnZcashAlone() =
        runTest {
            val h = Harness(LOCKS)
            h.saved = retiredFunded(ReversePhase.SETTLING)
            h.escrow = h.swap(SwapStage.CLAIMED)
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertEquals(1, h.receiveSubmits)

            h.received = ZcashTransactionStatus.Mined(DEPLOYMENT.zcashConfirmations.toLong())
            h.driver.advance()
            assertEquals(ReversePhase.COMPLETE, h.record.phase)
            assertEquals(1, h.receivePrepares, "the kept sweep is the one sent")
            assertEquals(1, h.forgets)
            assertTrue(h.relayed.isEmpty())
            assertEquals(0, h.accepts)
            assertEquals(0, h.tokenCollects, "a retired contract's maker is never asked")
        }

    @Test
    fun aRetiredEscrowStillHeldIsRefusedAndOneRefundedAndPaidOutEnds() =
        runTest {
            for (stage in listOf(SwapStage.OPEN, SwapStage.READY, SwapStage.REFUNDED)) {
                val h = Harness(LOCKS)
                h.saved = retiredFunded(ReversePhase.SETTLING)
                h.escrow = h.swap(stage)
                val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance() }
                assertEquals(AtomicSwapBlock.RETIRED_CONTRACT, refused.reason, "$stage")
                assertEquals(ReversePhase.SETTLING, h.record.phase)
                assertEquals(0, h.receivePrepares)
                assertEquals(0, h.forgets)
            }

            val refunded = Harness(LOCKS)
            refunded.saved = retiredFunded(ReversePhase.REFUNDING)
            refunded.paidOut = true
            refunded.escrow = refunded.swap(SwapStage.REFUNDED)
            refunded.driver.advance()
            assertEquals(ReversePhase.REFUNDED, refunded.record.phase)
            assertEquals(1, refunded.forgets)
            assertEquals(0, refunded.payoutCalls)
            assertTrue(refunded.relayed.isEmpty())
        }

    @Test
    fun aRetiredFundingThatNeverLandedEndsCalledOffAndOneThatStillMightIsRefused() =
        runTest {
            val pending = Harness(LOCKS)
            pending.saved = retiredFunded(ReversePhase.SENDING_USDC)
            val refused = assertFailsWith<AtomicSwapBlockedException> { pending.driver.advance() }
            assertEquals(AtomicSwapBlock.RETIRED_CONTRACT, refused.reason)
            assertEquals(ReversePhase.SENDING_USDC, pending.record.phase)
            assertTrue(pending.submissions.isEmpty(), "its funding is never sent again")

            val reverted = Harness(LOCKS)
            reverted.saved = retiredFunded(ReversePhase.SENDING_USDC)
            reverted.fundingState = TransactionStatus.REVERTED
            reverted.driver.advance()
            assertEquals(ReversePhase.CANCELLED, reverted.record.phase)

            val lapsed = Harness(LOCKS)
            lapsed.saved = retiredFunded(ReversePhase.CONFIRMING_ESCROW)
            lapsed.now = FUNDING + FUNDING_SETTLED_AFTER_SECONDS + 1
            lapsed.driver.advance()
            assertEquals(ReversePhase.CANCELLED, lapsed.record.phase)
            assertEquals(1, lapsed.forgets)
            assertTrue(lapsed.submissions.isEmpty())
        }

    // A conversion funded on the live contract, as kept on a retired one.
    private suspend fun retiredFunded(phase: ReversePhase): ReverseSwapRecord {
        val live = Harness()
        live.prepared()
        live.driver.fund(0)
        return live.record.copy(deployment = LOCKS, phase = phase)
    }

    @Test
    fun aKeptReadyIsSentAgainOnlyWhileTheEscrowIsOpen() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.driver.advance()
            h.driver.ready(0)
            h.driver.advance()
            assertEquals(2, h.readyCalls)

            h.escrow = h.swap(SwapStage.READY)
            h.driver.advance()
            assertEquals(2, h.readyCalls, "a ready escrow needs none")
            assertEquals(ReversePhase.SETTLING, h.record.phase)
        }

    @Test
    fun aReadyIsNeverSignedWithLessThanItsLifeLeftBeforeT0() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.now = READY - SIGNATURE_TTL_SECONDS - 1
            h.driver.advance()
            assertEquals(ReversePhase.AWAITING_READY, h.record.phase)
            h.driver.ready(0)
            assertEquals(READY - 1, h.record.ready?.deadline)

            val late = Harness()
            late.prepared()
            late.driver.fund(0)
            late.funded()
            late.paidIn = DEPOSIT
            late.now = READY - SIGNATURE_TTL_SECONDS
            late.driver.advance()
            assertEquals(ReversePhase.REFUND_WAIT, late.record.phase, "nothing left to approve: it can only come back")
            assertFailsWith<AtomicSwapBlockedException> { late.driver.ready(0) }
            assertEquals(0, late.readySignatures)
        }

    @Test
    fun pendingReadyIsNotResentAfterCancellationOrResignedAfterExpiry() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.paidIn = DEPOSIT
            h.driver.ready(0)
            h.now += 121
            h.restart()
            h.driver.advance()
            assertEquals(1, h.readySignatures)
            assertEquals(ReversePhase.REFUND_WAIT, h.record.phase)
            h.driver.cancel(0)
            h.driver.advance()
            assertEquals(1, h.readyCalls)
        }
}
