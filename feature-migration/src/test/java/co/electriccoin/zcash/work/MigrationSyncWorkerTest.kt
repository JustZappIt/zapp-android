package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MigrationSyncWorkerTest {

    private fun tx(
        id: Long,
        scheduledHeight: Long,
        isSent: Boolean = false,
        isProved: Boolean = false,
        anchorBoundaryHeight: Long? = null,
        isTransfer: Boolean = true,
    ) = MigrationTransferState(
        id = id,
        isTransfer = isTransfer,
        isSent = isSent,
        isProved = isProved,
        scheduledHeight = scheduledHeight,
        anchorBoundaryHeight = anchorBoundaryHeight,
    )

    private fun states(vararg transfers: MigrationTransferState, tipHeight: Long = 0L) =
        MigrationTransferStates(transfers = transfers.toList(), tipHeight = tipHeight)

    // ── decideLaneARun ─────────────────────────────────────────────────────────
    // A1: a woken Lane A ALWAYS syncs except (a) the privacy gate and (b) an imminent PROVED due.

    @Test
    fun `lane A steps aside inside a PROVED transaction's pre-due window`() {
        assertEquals(
            LaneARunDecision.SKIP_NEAR_DUE,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextProvedDueEpochSeconds = 1500,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
            )
        )
    }

    @Test
    fun `lane A runs when the proved due window is still far away`() {
        assertEquals(
            LaneARunDecision.RUN,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextProvedDueEpochSeconds = 5000,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
            )
        )
    }

    @Test
    fun `lane A runs when nothing proved is pending — an unproven due transfer never steps aside`() {
        // The precise replacement of the old overdue heuristic: an unproven due transaction's
        // proof can only come from THIS lane's sync, so nextProvedDue is null and Lane A runs.
        assertEquals(
            LaneARunDecision.RUN,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextProvedDueEpochSeconds = null,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
            )
        )
    }

    @Test
    fun `lane A skips when gate is blocked regardless of due window`() {
        assertEquals(
            LaneARunDecision.SKIP_GATE_BLOCKED,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextProvedDueEpochSeconds = 5000,
                privacyBufferSeconds = 600,
                isGateBlocked = true,
            )
        )
    }

    @Test
    fun `lane A gate block wins over the proved step-aside`() {
        assertEquals(
            LaneARunDecision.SKIP_GATE_BLOCKED,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextProvedDueEpochSeconds = 1500,
                privacyBufferSeconds = 600,
                isGateBlocked = true,
            )
        )
    }

    @Test
    fun `lane A steps aside when now equals exactly the window boundary`() {
        // now >= nextProvedDue - buffer → 1000 >= 1600 - 600 = 1000 → SKIP_NEAR_DUE
        assertEquals(
            LaneARunDecision.SKIP_NEAR_DUE,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextProvedDueEpochSeconds = 1600,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
            )
        )
    }

    // ── provableAtHeight ───────────────────────────────────────────────────────

    @Test
    fun `provableAt uses the committed boundary plus settle margin for transfers`() {
        val t = tx(11L, scheduledHeight = 500L, anchorBoundaryHeight = 144L)
        assertEquals(144L + SETTLE_MARGIN_BLOCKS, provableAtHeight(t))
    }

    @Test
    fun `provableAt falls back to the scheduled height for preparations (natural anchor)`() {
        val prep = tx(21L, scheduledHeight = 300L, anchorBoundaryHeight = null, isTransfer = false)
        assertEquals(300L + SETTLE_MARGIN_BLOCKS, provableAtHeight(prep))
    }

    // ── nextBoundaryWake ───────────────────────────────────────────────────────

    @Test
    fun `boundary wake targets the minimum provable-at height over unproven unsent transactions`() {
        val s = states(
            tx(11L, scheduledHeight = 500L, anchorBoundaryHeight = 288L),
            tx(12L, scheduledHeight = 400L, anchorBoundaryHeight = 144L),
            tx(13L, scheduledHeight = 300L, anchorBoundaryHeight = 100L, isProved = true),
            tx(14L, scheduledHeight = 200L, anchorBoundaryHeight = 50L, isSent = true),
        )
        // est=100, secondsPerBlock=10: t2 provable at 146 → (146-100)*10 = 460s.
        // t3 (proved) and t4 (sent) must not drive the wake despite lower boundaries.
        val wake = nextBoundaryWake(s, est = 100L, secondsPerBlock = 10L)
        assertNotNull(wake)
        assertEquals(12L, wake.txId)
        assertEquals(144L + SETTLE_MARGIN_BLOCKS, wake.wakeHeight)
        assertEquals(460.seconds, wake.delay)
    }

    @Test
    fun `boundary wake floors at 60s when the boundary has already settled`() {
        val s = states(tx(11L, scheduledHeight = 500L, anchorBoundaryHeight = 144L))
        // est far past the boundary → raw delay negative → floor.
        val wake = nextBoundaryWake(s, est = 10_000L, secondsPerBlock = 10L)
        assertNotNull(wake)
        assertEquals(MIN_LANE_A_BACKOFF_SECONDS.seconds, wake.delay)
    }

    @Test
    fun `boundary wake has no upper cap — a distant boundary waits it out`() {
        val s = states(tx(11L, scheduledHeight = 500_000L, anchorBoundaryHeight = 400_000L))
        // est=100_000, 75s/block → (400_002-100_000)*75 s — far beyond any cadence.
        val wake = nextBoundaryWake(s, est = 100_000L, secondsPerBlock = 75L)
        assertNotNull(wake)
        assertEquals(((400_000L + SETTLE_MARGIN_BLOCKS - 100_000L) * 75L).seconds, wake.delay)
    }

    @Test
    fun `boundary wake is null when the tip estimate is unavailable`() {
        val s = states(tx(11L, scheduledHeight = 500L, anchorBoundaryHeight = 144L))
        assertNull(nextBoundaryWake(s, est = -1L, secondsPerBlock = 75L))
    }

    @Test
    fun `boundary wake is null when no unproven unsent work remains`() {
        val s = states(
            tx(11L, scheduledHeight = 500L, anchorBoundaryHeight = 144L, isProved = true),
            tx(12L, scheduledHeight = 400L, anchorBoundaryHeight = 100L, isSent = true),
        )
        assertNull(nextBoundaryWake(s, est = 100L, secondsPerBlock = 75L))
    }

    @Test
    fun `boundary wake covers preparations via their natural anchor`() {
        val s = states(
            tx(21L, scheduledHeight = 200L, anchorBoundaryHeight = null, isTransfer = false),
            tx(11L, scheduledHeight = 500L, anchorBoundaryHeight = 288L),
        )
        val wake = nextBoundaryWake(s, est = 100L, secondsPerBlock = 10L)
        assertNotNull(wake)
        assertEquals(21L, wake.txId)
        assertEquals(200L + SETTLE_MARGIN_BLOCKS, wake.wakeHeight)
    }

    // ── nextProvedDueEpochSeconds ──────────────────────────────────────────────

    @Test
    fun `proved due filters to proved and unsent only`() {
        val now = 1_000_000L
        val est = 800_000L
        val s = states(
            tx(11L, scheduledHeight = 800_005L),                                  // unproven — ignored
            tx(12L, scheduledHeight = 800_010L, isProved = true),                 // 10 blocks out
            tx(13L, scheduledHeight = 800_001L, isProved = true, isSent = true),  // sent — ignored
        )
        val result = nextProvedDueEpochSeconds(s, est, now, secondsPerBlock = 75L)
        assertEquals(now + 10L * 75L, result)
    }

    @Test
    fun `proved due is null when nothing proved is pending`() {
        val s = states(tx(11L, scheduledHeight = 800_005L))
        assertNull(nextProvedDueEpochSeconds(s, est = 800_000L, nowEpochSeconds = 0L, secondsPerBlock = 75L))
    }

    @Test
    fun `proved due is null when estimator is sentinel -1`() {
        val s = states(tx(11L, scheduledHeight = 800_005L, isProved = true))
        assertNull(nextProvedDueEpochSeconds(s, est = -1L, nowEpochSeconds = 0L, secondsPerBlock = 75L))
    }

    @Test
    fun `proved due clamps an already-passed height to now`() {
        val now = 1_000_000L
        val s = states(tx(11L, scheduledHeight = 700_000L, isProved = true))
        assertEquals(now, nextProvedDueEpochSeconds(s, est = 800_000L, nowEpochSeconds = now, secondsPerBlock = 75L))
    }

    // ── completionSweepDelay ───────────────────────────────────────────────────

    @Test
    fun `completion sweep targets the last unsent scheduled height plus buffer`() {
        val s = states(
            tx(11L, scheduledHeight = 800_010L, isProved = true),
            tx(12L, scheduledHeight = 800_050L, isProved = true),
            tx(13L, scheduledHeight = 800_100L, isSent = true, isProved = true),
        )
        // est=800_000, 10s/block, buffer=180 → (800_050-800_000)*10 + 180 = 680s
        assertEquals(
            680.seconds,
            completionSweepDelay(s, est = 800_000L, secondsPerBlock = 10L, privacyBufferSeconds = 180L)
        )
    }

    @Test
    fun `completion sweep floors at 60s when everything unsent is already past due`() {
        val s = states(tx(11L, scheduledHeight = 100L, isProved = true))
        assertEquals(
            MIN_LANE_A_BACKOFF_SECONDS.seconds,
            completionSweepDelay(s, est = 10_000L, secondsPerBlock = 10L, privacyBufferSeconds = 0L)
        )
    }

    @Test
    fun `completion sweep is null when all transactions are sent`() {
        val s = states(tx(11L, scheduledHeight = 100L, isSent = true, isProved = true))
        assertNull(completionSweepDelay(s, est = 200L, secondsPerBlock = 10L, privacyBufferSeconds = 180L))
    }

    @Test
    fun `completion sweep is null when the tip estimate is unavailable`() {
        val s = states(tx(11L, scheduledHeight = 100L, isProved = true))
        assertNull(completionSweepDelay(s, est = -1L, secondsPerBlock = 10L, privacyBufferSeconds = 180L))
    }

    // ── laneASkipReArmDelay ────────────────────────────────────────────────────

    @Test
    fun `near-due skip re-arm waits out the proved window`() {
        // nextProvedDue=2000, buffer=300, now=1600 → remaining = 2000+300-1600 = 700s
        assertEquals(
            700.seconds,
            laneASkipReArmDelay(
                decision = LaneARunDecision.SKIP_NEAR_DUE,
                nowEpochSeconds = 1600,
                nextProvedDueEpochSeconds = 2000,
                privacyBufferSeconds = 300,
            )
        )
    }

    @Test
    fun `near-due skip re-arm never goes below the 60s floor`() {
        val d = laneASkipReArmDelay(
            decision = LaneARunDecision.SKIP_NEAR_DUE,
            nowEpochSeconds = 5000,
            nextProvedDueEpochSeconds = 1500,
            privacyBufferSeconds = 300,
        )
        assertEquals(MIN_LANE_A_BACKOFF_SECONDS.seconds, d)
    }

    @Test
    fun `gate-blocked re-arm waits out the privacy buffer`() {
        assertEquals(
            600.seconds,
            laneASkipReArmDelay(
                decision = LaneARunDecision.SKIP_GATE_BLOCKED,
                nowEpochSeconds = 1000,
                nextProvedDueEpochSeconds = null,
                privacyBufferSeconds = 600,
            )
        )
    }

    // ── nextEstimatedDueEpochSeconds (Lane B's window basis — kind-agnostic) ───

    @Test
    fun `nextEstimatedDue returns null when states has no pending transfers`() {
        val s = states(tx(11L, scheduledHeight = 100L, isSent = true), tipHeight = 90L)
        assertNull(nextEstimatedDueEpochSeconds(s, est = 90L))
    }

    @Test
    fun `nextEstimatedDue returns null when estimator is sentinel -1`() {
        val s = states(tx(11L, scheduledHeight = 200L), tipHeight = 100L)
        assertNull(nextEstimatedDueEpochSeconds(s, est = -1L))
    }

    @Test
    fun `nextEstimatedDue returns null when transfers list is empty`() {
        val s = states(tipHeight = 100L)
        assertNull(nextEstimatedDueEpochSeconds(s, est = 100L))
    }

    @Test
    fun `nextEstimatedDue computes min over pending transactions`() {
        val now = 1_000_000L
        val est = 800_000L
        // t1: 10 blocks remaining → 750s; t2: 100 blocks → 7500s; min = now + 750
        val s = states(
            tx(11L, scheduledHeight = 800_010L),
            tx(12L, scheduledHeight = 800_100L),
            tipHeight = est,
        )
        assertEquals(now + 750L, nextEstimatedDueEpochSeconds(s, est = est, nowEpochSeconds = now))
    }

    @Test
    fun `nextEstimatedDue includes unsent preparations`() {
        // R1/A5: preparations are part of the surfaced states — Lane B's window basis must see
        // a due prep (the engine serves preps for broadcast too), so no window can sleep past it.
        val now = 1_000_000L
        val est = 800_000L
        val s = states(
            tx(21L, scheduledHeight = 800_004L, isTransfer = false),
            tx(11L, scheduledHeight = 800_100L),
            tipHeight = est,
        )
        assertEquals(now + 4L * 75L, nextEstimatedDueEpochSeconds(s, est = est, nowEpochSeconds = now))
    }

    @Test
    fun `nextEstimatedDue clamps negative block difference to zero`() {
        val now = 1_000_000L
        val est = 800_100L
        val s = states(tx(11L, scheduledHeight = 800_000L), tipHeight = est)
        assertEquals(now, nextEstimatedDueEpochSeconds(s, est = est, nowEpochSeconds = now))
    }

    @Test
    fun `nextEstimatedDue ignores already-sent transfers`() {
        val now = 1_000_000L
        val est = 800_000L
        val s = states(
            tx(11L, scheduledHeight = 800_001L, isSent = true),
            tx(12L, scheduledHeight = 800_020L),
            tipHeight = est,
        )
        assertEquals(now + 1500L, nextEstimatedDueEpochSeconds(s, est = est, nowEpochSeconds = now))
    }

    // ── shouldLaneAStop (F3) ──────────────────────────────────────────────────
    // Lane A stops only on terminal states: Complete, or RequiresAttention with a terminal reason
    // (InvalidTransfer / TransferExpired). SyncRequiredBeforeNext is NOT terminal — Lane A heals it.

    @Test
    fun `lane A stops when the migration is complete`() {
        assertTrue(shouldLaneAStop(MigrationState.Complete))
    }

    @Test
    fun `lane A stops on terminal RequiresAttention reasons`() {
        assertTrue(shouldLaneAStop(MigrationState.RequiresAttention(AttentionReason.InvalidTransfer(11L))))
        assertTrue(shouldLaneAStop(MigrationState.RequiresAttention(AttentionReason.TransferExpired)))
    }

    @Test
    fun `lane A keeps running on SyncRequiredBeforeNext`() {
        // Lane A's own sync is what heals this reason — it must not stop.
        assertFalse(shouldLaneAStop(MigrationState.RequiresAttention(AttentionReason.SyncRequiredBeforeNext)))
    }

    @Test
    fun `lane A keeps running while in progress or pre-commit`() {
        assertFalse(shouldLaneAStop(MigrationState.InProgress(MigrationProgress(0, 3, null))))
        assertFalse(shouldLaneAStop(MigrationState.NotStarted))
        assertFalse(shouldLaneAStop(MigrationState.SplitPendingConfirmation))
        assertFalse(shouldLaneAStop(MigrationState.ReadyToPropose))
    }
}
