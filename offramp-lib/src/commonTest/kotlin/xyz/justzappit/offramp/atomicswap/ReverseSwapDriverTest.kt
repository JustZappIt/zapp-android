// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.BigInteger
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
            assertTrue(h.submissions.all { it == saved })
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
                    0,
                    AMOUNT,
                    0,
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
                quote.copy(refundNote = LEGACY_NOTE),
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
    fun cancellationNeverRevealsWithoutConfirmedLockAndMargin() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.driver.cancel(0)
            assertEquals(1, h.lockCalls)
            assertEquals(0, h.refundCalls)
            h.refundUntil = h.now + REVEAL_MARGIN_SECONDS
            h.driver.advance()
            assertEquals(0, h.refundCalls)
            assertEquals(0, h.payoutSignatures, "the payout is signed only when it goes out")
            h.refundUntil = h.now + 600
            h.driver.advance()
            assertEquals(1, h.refundCalls)
            assertEquals(1, h.payoutSignatures)
            assertNotNull(h.record.payout)
        }

    @Test
    fun afterReadyRefundWaitsForDeadlineAndAlternatingTurn() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.escrow = h.swap(SwapStage.READY)
            h.driver.cancel(0)
            assertEquals(0, h.lockCalls)
            h.now = REFUND
            h.claimUntil = REFUND + 500
            h.driver.advance()
            assertEquals(0, h.lockCalls)
            h.now = REFUND + 502
            h.refundUntil = h.now - 1
            h.driver.advance()
            assertEquals(0, h.lockCalls)
            h.now += 601
            h.driver.advance()
            assertEquals(1, h.lockCalls)
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
