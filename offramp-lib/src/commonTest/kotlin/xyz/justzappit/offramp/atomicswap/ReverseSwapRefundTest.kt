// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReverseSwapRefundTest : ReverseSwapDriverFixtures() {
    @Test
    fun settlementStopsRefundsBeforeTheZecTransferCanBePrepared() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.escrow = h.swap(SwapStage.CLAIMED)
            h.interruptReceive = true

            assertFailsWith<IllegalStateException> { h.driver.advance() }
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertTrue(h.record.isSettled)
            assertNull(h.record.receive)
            assertFalse(checkNotNull(h.record.status as? ReverseSwapStatus.UnderWay).cancellable)
            assertFailsWith<ReverseRefundUnavailableException> { h.driver.cancel(0) }
            assertFalse(h.record.cancelRequested)
            assertEquals(0, h.lockCalls)

            h.interruptReceive = false
            h.driver.advance()
            assertNotNull(h.record.receive)
        }

    @Test
    fun aRefundClickRacingSettlementReportsThatSettlementWon() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.escrow = h.swap(SwapStage.CLAIMED)

            assertFailsWith<ReverseRefundUnavailableException> { h.driver.cancel(0) }
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertNotNull(h.record.receive)
            assertEquals(0, h.lockCalls)
            assertEquals(0, h.refundCalls)
        }

    @Test
    fun aRefundClickRacingSettlementAndTransferFailureStillReportsSettlement() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.escrow = h.swap(SwapStage.CLAIMED)
            h.interruptReceive = true

            assertFailsWith<ReverseRefundUnavailableException> { h.driver.cancel(0) }
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertNull(h.record.receive)
            assertEquals(0, h.lockCalls)
        }

    @Test
    fun refundButtonsStopAtSettlementAndWhileARefundIsBeingPaid() =
        runTest {
            val h = Harness()
            h.prepared()
            val allowed =
                setOf(
                    ReversePhase.ACCEPTING,
                    ReversePhase.AWAITING_FUNDING,
                    ReversePhase.SENDING_USDC,
                    ReversePhase.CONFIRMING_ESCROW,
                    ReversePhase.RECEIVING_ZEC,
                    ReversePhase.AWAITING_READY,
                    ReversePhase.SETTLING,
                    ReversePhase.REFUND_WAIT,
                )
            for (phase in ReversePhase.entries) {
                val status = h.record.copy(phase = phase).status as? ReverseSwapStatus.UnderWay
                assertEquals(phase in allowed, status?.cancellable == true, phase.name)
            }
        }

    @Test
    fun refundPayoutFailureAndRescueCanResumeWithoutFundingAgain() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.escrow = h.swap(SwapStage.REFUNDED)
            h.interruptPayout = true
            assertFailsWith<IllegalStateException> { h.driver.advance() }
            assertNotNull(h.record.payout)
            h.restart()
            h.interruptPayout = false
            h.driver.advance()
            assertEquals(2, h.payoutCalls)
            h.paidOut = true
            h.driver.advance()
            assertEquals(ReversePhase.REFUNDED, h.record.phase)
            assertEquals(1, h.forgets)
            for (balance in listOf(Usdc6.ZERO, Usdc6.ofMicros(100_000))) {
                h.vault = balance
                assertFalse(h.driver.canRescue(0))
                h.driver.rescue(0)
                assertEquals(0, h.rescueCalls)
                assertEquals(0, h.rescueSignatures)
            }
            h.vault = Usdc6.ofMicros(900_000)
            assertTrue(h.driver.canRescue(0))
            h.interruptPayout = true
            assertFailsWith<IllegalStateException> { h.driver.rescue(0) }
            assertEquals(ReversePhase.REFUNDED, h.record.phase)
            assertFalse(h.record.underWay, "a rescue never holds up another conversion")
            h.restart()
            h.interruptPayout = false
            h.driver.advance()
            assertEquals(1, h.rescueCalls, "a rescue is never retried in the background")
            assertTrue(h.driver.canRescue(0))
            h.driver.rescue(0)
            assertEquals(2, h.rescueCalls)
            assertEquals(2, h.rescueSignatures)
            h.vault = Usdc6.ZERO
            assertFalse(h.driver.canRescue(0))
            assertEquals(1, h.prepares)
        }

    @Test
    fun aFundingTheChainRevertedCancelsAndForgetsTheJointAccount() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.fundingState = TransactionStatus.REVERTED
            h.driver.advance()
            assertEquals(ReversePhase.CANCELLED, h.record.phase)
            assertEquals(1, h.submissions.size)
            assertEquals(1, h.forgets)
        }

    @Test
    fun aFundingTheNodeKnowsWaitsForItsEscrowWithoutBeingSentAgain() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            for (state in listOf(TransactionStatus.PENDING, TransactionStatus.CONFIRMED)) {
                h.fundingState = state
                h.driver.advance()
                assertEquals(ReversePhase.CONFIRMING_ESCROW, h.record.phase)
            }
            assertEquals(1, h.submissions.size)
        }

    @Test
    fun aFundingTheNodeNeverSawIsSentAgainUntilTheUserCallsItOff() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.driver.advance()
            assertEquals(2, h.submissions.size)
            h.driver.cancel(0)
            h.driver.advance()
            assertEquals(2, h.submissions.size)
            assertEquals(ReversePhase.REFUND_WAIT, h.record.phase)
        }

    @Test
    fun noEscrowWellPastTheFundingDeadlineCancels() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.fundingState = TransactionStatus.PENDING
            h.now = FUNDING + FUNDING_SETTLED_AFTER_SECONDS
            h.driver.advance()
            assertEquals(ReversePhase.CONFIRMING_ESCROW, h.record.phase)
            h.fundingState = TransactionStatus.UNKNOWN
            h.now += 1
            h.driver.advance()
            assertEquals(ReversePhase.CANCELLED, h.record.phase)
            assertEquals(1, h.submissions.size)
        }

    @Test
    fun theRefundSecretStaysSecretThroughANodeMinutesBehind() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.driver.cancel(0)
            h.refundUntil = h.now + 600
            h.clock = h.now + 4 * 60
            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance() }
            assertEquals(AtomicSwapBlock.CHAIN_LAGGING, blocked.reason)
            assertEquals(0, h.refundCalls)
            h.clock = h.now + 90
            h.driver.advance()
            assertEquals(1, h.refundCalls)
        }

    @Test
    fun receiveResumesSameBytesAndRebuildsOnlyAfterProvenExpiry() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.escrow = h.swap(SwapStage.CLAIMED)
            h.driver.advance()
            val original = h.record.receive
            h.restart()
            h.driver.advance()
            assertEquals(original, h.record.receive)
            assertEquals(1, h.receivePrepares)
            h.received = ZcashTransactionStatus.Expired
            h.driver.advance()
            assertEquals(2, h.receivePrepares)
        }

    @Test
    fun unconfirmedEscrowCannotAuthorizeReady() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.paidIn = DEPOSIT
            h.block = 10
            assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            assertEquals(0, h.readySignatures)
        }

    @Test
    fun completedReadyDeadlineStartsRecoveryWithoutAnotherAuthorization() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.escrow = h.swap(SwapStage.READY)
            h.now = REFUND
            h.driver.advance()
            assertEquals(1, h.lockCalls)
            assertEquals(0, h.refundCalls)
            assertEquals(0, h.readySignatures)
        }

    @Test
    fun readyRequiresAUsableSweepAndPersistsItsFeeEstimate() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.paidIn = DEPOSIT
            h.sweepFee = DEPOSIT
            val unusable = assertFailsWith<AtomicSwapBlockedException> { h.driver.ready(0) }
            assertEquals(AtomicSwapBlock.MISMATCH, unusable.reason)
            assertEquals(0, h.readySignatures)
            h.sweepFee = 15_000
            h.driver.advance()
            assertEquals(DEPOSIT - 15_000, h.record.receiveEstimate?.receivedZat)
            h.driver.ready(0)
            h.restart()
            assertEquals(15_000, h.record.receiveEstimate?.feeZat)
        }

    @Test
    fun approvalAndOpenUseExactEscrowAmountAndBusinessRoles() =
        runTest {
            val h = Harness()
            h.prepared()
            val fee = Usdc6.ofMicros(250_000)
            val calls = ReverseFundingCalls.encode(h.record, ByteArray(65), fee)
            assertEquals(listOf(TOKEN, CONTRACT, TOKEN), calls.map { it.first })
            val approval = AbiDecoder(calls[0].second.drop(4).toByteArray())
            assertEquals(CONTRACT, approval.address(0))
            assertEquals(AMOUNT.micros, approval.uint(1))
            val open = AbiDecoder(calls[1].second.drop(4).toByteArray())
            assertEquals(MAKER, open.address(0))
            assertEquals(USER, open.address(1))
            assertEquals(TOKEN, open.address(2))
            assertEquals(AMOUNT.micros, open.uint(3))
            assertTrue(open.word(10).contentEquals(NOTE.bytes))
            val payment = AbiDecoder(calls[2].second.drop(4).toByteArray())
            assertEquals(h.record.deployment.relayer, payment.address(0))
            assertEquals(fee.micros, payment.uint(1))
        }

    @Test
    fun anEarlierRefundCanBeRecoveredWhileANewerPreviewIsActive() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.escrow = h.swap(SwapStage.REFUNDED)
            h.paidOut = true
            h.driver.advance()
            assertEquals(ReversePhase.REFUNDED, h.record.phase)
            h.escrow = null

            h.driver.quote(AMOUNT)
            assertEquals(1, h.record.index)
            h.escrow = h.swap(SwapStage.REFUNDED)

            assertTrue(h.driver.canRescue(0))
            h.driver.rescue(0)
            assertEquals(1, h.rescueCalls)
            assertEquals(1, h.record.index, "the preview stays the active conversion")
            assertEquals(ReversePhase.QUOTED, h.record.phase)
            assertNotNull(h.kept(0).rescue)
        }

    @Test
    fun goingAheadWithAQuoteThatRanOutIsRefusedAndNothingIsAccepted() =
        runTest {
            val h = Harness()
            h.driver.quote(AMOUNT)
            h.now = FUNDING

            val expired = assertFailsWith<AtomicSwapBlockedException> { h.driver.goAhead(0) }
            assertEquals(AtomicSwapBlock.QUOTE_EXPIRED, expired.reason)
            assertEquals(ReversePhase.QUOTED, h.record.phase)
            assertEquals(0, h.accepts)
        }

    @Test
    fun aPreviewIsNeverFundedOrAcceptedBeforeTheUserGoesAhead() =
        runTest {
            val h = Harness()
            h.driver.quote(AMOUNT)

            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            assertEquals(0, h.accepts)
            assertEquals(0, h.prepares)
        }

    @Test
    fun goingAheadThenFundingAcceptsImportsAndPaysInOneGo() =
        runTest {
            val h = Harness()
            h.driver.quote(AMOUNT)

            h.driver.goAhead(0)
            h.driver.fund(0)

            assertEquals(1, h.accepts)
            assertEquals(ACCOUNT, h.record.account)
            assertEquals(ReversePhase.CONFIRMING_ESCROW, h.record.phase)
            assertEquals(1, h.submissions.size)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelDoesNotWaitForTheFundingProofAndTheProvedPaymentIsNeverSent() =
        runTest {
            val h = Harness()
            h.prepared()
            h.proving = CompletableDeferred()

            val funding = async { runCatching { h.driver.fund(0) } }
            runCurrent()
            h.driver.cancel(0)
            assertTrue(h.record.cancelRequested)
            h.proving?.complete(Unit)

            assertTrue(funding.await().isFailure)
            assertNull(h.record.funding)
            assertTrue(h.submissions.isEmpty())
        }

    @Test
    fun aWaitThatChangesNothingIsNotSavedAgain() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING_ZEC, h.record.phase)
            val saves = h.saves

            repeat(3) { h.driver.advance() }

            assertEquals(saves, h.saves)
        }

    @Test
    fun aMakerOnAnotherZcashNetworkOrReturningTokensUnderAKeyNotPinnedIsNeverQuoted() =
        runTest {
            val h = Harness()
            h.info = h.info.copy(zcashNetwork = SwapZcashNetwork.MAINNET)

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(AMOUNT) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason)
            h.info = h.info.copy(zcashNetwork = SwapZcashNetwork.TESTNET, tokenReturnKey = "a key no build pins")
            val marking = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(AMOUNT) }
            assertEquals(AtomicSwapBlock.TOKENS_REFUSED, marking.reason)
            assertEquals(0, h.nextIndex)
        }

    @Test
    fun aRefundPayoutNeverPaysARelayerThatIsTheMaker() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.escrow = h.swap(SwapStage.REFUNDED)
            h.relayer = MAKER

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance() }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason)
            assertEquals(0, h.payoutCalls)
        }
}
