// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.TxHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicSwapClaimTest : AtomicSwapDriverFixtures() {
    @Test
    fun aRefundAfterADepositThatNeverWentOutSweepsNothing() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(deposit = SwapDeposit.Started)
            h.chain.stage = SwapStage.REFUNDED

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_CANCELLED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.sweeps.isEmpty())
        }

    @Test
    fun aRefundAfterAnExpiredDepositSweepsNothing() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.zcash.status[txId("deposit1")] = ZcashTransactionStatus.Expired
            h.chain.stage = SwapStage.REFUNDED

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_CANCELLED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.sweeps.isEmpty())
        }

    @Test
    fun aRefundWaitsForADepositThatMayStillBeMinedWithoutSendingIt() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.REFUNDED

            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.DEPOSIT_UNSETTLED), h.driver.advance(record))
            assertEquals(1, h.zcash.submitted().size)
            assertTrue(h.zcash.sweeps.isEmpty())
        }

    @Test
    fun aRefundAfterAnInterruptedDepositThatWentOutSweepsIt() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(deposit = SwapDeposit.Started)
            h.zcash.created = transaction("earlier")
            h.zcash.status[txId("earlier")] = ZcashTransactionStatus.Mined(5)
            h.chain.stage = SwapStage.REFUNDED

            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.REFUNDING), h.driver.advance(record))
            h.zcash.status[txId("sweep1")] = ZcashTransactionStatus.Mined(3)

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.Refunded(txId("sweep1"), RefundCause.MAKER_CANCELLED)),
                h.driver.advance(h.store.record!!),
            )
            assertEquals(1, h.zcash.sweeps.size)
        }

    @Test
    fun aRefundSweepIsKeptBeforeItIsSentAndEndsOnlyWithItsConfirmations() =
        runTest {
            val h = Harness()
            val record = h.minedDeposit()
            h.chain.stage = SwapStage.REFUNDED
            h.chain.secret = ByteArray(32) { 0x0e }

            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.REFUNDING), h.driver.advance(record))
            assertEquals(listOf(Triple(record.index, MAKER_SHARE, record.zcashHeight)), h.zcash.sweeps)
            assertEquals(txId("sweep1"), checkNotNull(h.store.record).sweep?.txId)
            assertEquals(txId("sweep1"), h.zcash.submitted().last())

            h.zcash.status[txId("sweep1")] = ZcashTransactionStatus.Mined(2)
            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.REFUNDING), h.driver.advance(h.store.record!!))
            assertEquals(0, h.zcash.forgotten)

            h.zcash.status[txId("sweep1")] = ZcashTransactionStatus.Mined(3)
            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.Refunded(txId("sweep1"), RefundCause.MAKER_CANCELLED)),
                h.driver.advance(h.store.record!!),
            )
            assertEquals(1, h.zcash.sweeps.size)
            assertEquals(1, h.zcash.forgotten, "the deposit account goes once its sweep is final")
        }

    @Test
    fun anExpiredSweepIsBuiltAgain() =
        runTest {
            val h = Harness()
            val record = h.minedDeposit()
            h.chain.stage = SwapStage.REFUNDED
            h.driver.advance(record)
            h.zcash.status[txId("sweep1")] = ZcashTransactionStatus.Expired

            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.REFUNDING), h.driver.advance(h.store.record!!))
            assertEquals(txId("sweep2"), checkNotNull(h.store.record).sweep?.txId)
            assertEquals(0, h.zcash.forgotten)
        }

    @Test
    fun aSweepWaitsUntilTheRefundedDepositIsSpendable() =
        runTest {
            val h = Harness()
            val record = h.minedDeposit()
            h.chain.stage = SwapStage.REFUNDED
            h.zcash.spendable = false

            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.REFUNDING), h.driver.advance(record))
            assertNull(h.store.record?.sweep)
        }

    @Test
    fun aRefundLockedAfterT0IsASwapNobodyClaimedInTime() =
        runTest {
            val h = Harness()
            val record = h.minedDeposit()
            h.chain.stage = SwapStage.REFUNDED
            h.chain.refundLockUntil = h.chain.t0 + 300 + 10 + LOCK_SECONDS
            h.driver.advance(record)
            h.zcash.status[txId("sweep1")] = ZcashTransactionStatus.Mined(3)

            val refunded = AtomicSwapOutcome.Refunded(txId("sweep1"), RefundCause.NOT_CLAIMED_IN_TIME)
            assertEquals(AtomicSwapStep.Finished(refunded), h.driver.advance(h.store.record!!))
        }

    @Test
    fun aSwapLeftUnderTheSameIdByAnEarlierUseOfTheIndexIsNeverPaid() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.makerShare = SwapShare.of(ByteArray(64) { 0x0b })
            h.chain.stage = SwapStage.CLAIMED
            h.chain.paidOut = true

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH)),
                h.driver.advance(record),
            )
            assertNull(h.store.record?.payoutTx)
        }

    @Test
    fun theShareStaysSecretWhenRailgunIsNotTakingPayouts() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.chain.railgunAccepts = false

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RAILGUN_CLOSED, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aPayoutWaitsWhileRailgunIsNotTakingIt() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED
            h.chain.railgunAccepts = false

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RAILGUN_CLOSED, blocked.reason)
            assertTrue("/v1/payout" !in h.paths)
        }

    @Test
    fun theShareStaysSecretUnderALockAboutToLapse() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.chain.claimLockUntil = NOW + 60

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.CLAIM_LOCK_LAPSING, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun theShareStaysSecretThroughANodeMinutesBehind() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.chain.claimLockUntil = NOW + LOCK_SECONDS
            h.clock = NOW + 4 * 60

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.CHAIN_LAGGING, blocked.reason)
            assertTrue(h.paths.none { it == "/v1/claim" || it == "/v1/lock-claim" })
        }

    @Test
    fun aDeviceClockAMinuteOffStillClaims() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.clock = NOW + 90

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
        }

    @Test
    fun aPayoutNeverPaysTheRelayerMoreThanTheOfferSaid() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.relayerFee = "30000"
            h.chain.stage = SwapStage.READY

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RELAYER_FEE, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aRelayerOtherThanTheDeploymentsIsRefused() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.relayer = AUTH
            h.chain.stage = SwapStage.READY

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, blocked.reason)
            assertTrue("/v1/lock-claim" !in h.paths)
        }

    @Test
    fun anUnreachableRelayerIsReportedAsSuch() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.unreachable = "/v1/lock-claim"

            val failed = assertFailsWith<AtomicSwapHttpException.Unreachable> { h.driver.advance(record) }
            assertEquals(AtomicSwapService.RELAYER, failed.service)
            assertNull(h.store.record?.outcome)
        }

    @Test
    fun aClaimRevealedBeforeAnInterruptionOnlyNeedsItsPayout() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertEquals("/v1/payout", h.paths.last())
            assertTrue("/v1/claim" !in h.paths)
            assertEquals(TxHash.fromHex(hash(0)), h.store.record?.payoutTx)
        }

    @Test
    fun onlyASwapThatNeverReachedTheChainCanBeAbandoned() =
        runTest {
            val h = Harness()
            val record = h.accepted()

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.abandon(record) }
            assertEquals(AtomicSwapBlock.UNDER_WAY_ON_CHAIN, refused.reason)

            h.chain.opened = false
            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.NEVER_OPENED)),
                h.driver.abandon(record),
            )
        }

    @Test
    fun slowStepsAreAnnouncedBeforeTheyStart() =
        runTest {
            val h = Harness()
            val heard = mutableListOf<AtomicSwapActivity>()
            h.driver.advance(h.accepted(), heard::add)
            h.chain.stage = SwapStage.READY
            h.driver.advance(h.store.record!!, heard::add)

            assertEquals(listOf(AtomicSwapActivity.DEPOSITING, AtomicSwapActivity.CLAIMING), heard)
        }

    @Test
    fun aFinishedSwapStaysFinished() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord().copy(end = SwapEnd(AtomicSwapOutcome.Paid, NOW))

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
        }

    @Test
    fun aMakerOnAnotherZcashNetworkIsRefusedBeforeAnIndexIsSpent() =
        runTest {
            val h = Harness()
            h.zcashNetwork = "mainnet"

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(ONE_UNIT) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason)
            assertEquals(listOf("/v1/info"), h.paths)

            h.zcashNetwork = "testnet"
            assertEquals(0, h.driver.quote(ONE_UNIT).index)
        }

    @Test
    fun theMakersInfoIsCheckedOnceForEveryQuoteAfter() =
        runTest {
            val h = Harness()
            h.driver.quote(ONE_UNIT)
            h.driver.quote(ONE_UNIT)

            assertEquals(1, h.paths.count { it == "/v1/info" })
        }

    @Test
    fun aRefusalWithAKnownCodeEndsAsTheCodeSays() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.BadRequest
            h.acceptCode = "unknownQuote"

            val record = h.driver.accept(h.driver.quote(ONE_UNIT))

            assertEquals(AtomicSwapOutcome.NothingSent(NothingSentCause.QUOTE_EXPIRED), record.outcome)
        }

    @Test
    fun aRefusalThatMayFollowAnOpenIsNotTakenForNothingSent() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.BadRequest
            h.acceptCode = "internal"

            assertFailsWith<AtomicSwapHttpException.Refused> { h.driver.accept(h.driver.quote(ONE_UNIT)) }
            assertNull(h.store.record?.outcome)
        }

    @Test
    fun aMismatchedSwapAfterTheDepositIsNeverClaimed() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.payoutNote = NoteCommitment.of(ByteArray(32) { 6 })
            h.chain.stage = SwapStage.READY

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.MISMATCH, blocked.reason)
            assertTrue(h.paths.none { it == "/v1/lock-claim" || it == "/v1/claim" })
            assertNull(h.store.record?.outcome)

            h.chain.stage = SwapStage.CLAIMED
            assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertTrue("/v1/payout" !in h.paths)
        }

    @Test
    fun theMakersTurnAfterOurClaimLockLapsedIsWaitedOutWithoutAskingTheRelayer() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.chain.claimLockUntil = NOW - 10

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.CLAIM_LOCK_LAPSING, blocked.reason)
            assertTrue("/v1/lock-claim" !in h.paths)

            h.chain.now = NOW - 10 + LOCK_SECONDS
            h.clock = h.chain.now
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue("/v1/lock-claim" in h.paths)
        }

    @Test
    fun aPayoutAfterTheClaimNeverRaisesTheReviewedFee() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED
            h.relayerFee = "30000"

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RELAYER_FEE, blocked.reason)
            assertTrue("/v1/payout" !in h.paths)
            assertTrue(h.keys.signedFees.isEmpty())
            assertNull(h.store.record?.outcome)
        }

    @Test
    fun aSplitClaimCannotAuthorizeAHigherFeeAndResumesAtTheReviewedFee() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.claimPaysOut = false
            h.feeAfterClaim = "100000"

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RELAYER_FEE, blocked.reason)
            assertTrue(h.bodies.getValue("/v1/claim").contains("\"fee\":\"20000\""))
            assertTrue("/v1/payout" !in h.paths)
            assertEquals(listOf(record.relayerFee), h.keys.signedFees)
            assertNull(h.store.record?.outcome)

            h.relayerFee = "20000"
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(h.store.record!!))
            assertTrue(h.bodies.getValue("/v1/payout").contains("\"fee\":\"20000\""))
        }

    @Test
    fun aPayoutNeverPaysTheRelayerMoreThanTheDeploymentAllows() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED
            h.relayerFee = "100001"

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RELAYER_FEE, blocked.reason)
            assertTrue("/v1/payout" !in h.paths)
        }

    @Test
    fun aRelayerThatIsTheMakerIsRefused() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.relayer = MAKER
            h.chain.stage = SwapStage.READY

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, blocked.reason)
        }
}
