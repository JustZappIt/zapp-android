package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MigrationSyncWorkerTest {

    // ── decideLaneARun ─────────────────────────────────────────────────────────

    @Test
    fun `lane A skips inside the pre-due window`() {
        assertEquals(
            LaneARunDecision.SKIP_NEAR_DUE,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = 1500,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
            )
        )
    }

    @Test
    fun `lane A runs when no transfer is near due`() {
        assertEquals(
            LaneARunDecision.RUN,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = 5000,
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
                nextEstimatedDueEpochSeconds = 5000,
                privacyBufferSeconds = 600,
                isGateBlocked = true,
            )
        )
    }

    @Test
    fun `lane A skips gate blocked even when inside pre-due window`() {
        assertEquals(
            LaneARunDecision.SKIP_GATE_BLOCKED,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = 1500,
                privacyBufferSeconds = 600,
                isGateBlocked = true,
            )
        )
    }

    @Test
    fun `lane A runs when nextEstimatedDue is null (no pending transfers)`() {
        assertEquals(
            LaneARunDecision.RUN,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = null,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
            )
        )
    }

    @Test
    fun `lane A skips when now equals exactly the window boundary`() {
        // now >= nextDue - buffer → 1000 >= 1600 - 600 = 1000 → SKIP_NEAR_DUE
        assertEquals(
            LaneARunDecision.SKIP_NEAR_DUE,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = 1600,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
            )
        )
    }

    @Test
    fun `lane A overrides the step-aside when the due transfer is long overdue and unsent`() {
        // Live-observed livelock: transfer due "now" forever (estimate clamps to now when past
        // due), unproven, so Lane B can never broadcast — Lane A must run, not step aside.
        assertEquals(
            LaneARunDecision.RUN_OVERDUE_UNSENT,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = 1000,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
                overdueUnsentSeconds = 270,
            )
        )
    }

    @Test
    fun `lane A still steps aside when overdue is under the override threshold`() {
        assertEquals(
            LaneARunDecision.SKIP_NEAR_DUE,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = 1000,
                privacyBufferSeconds = 600,
                isGateBlocked = false,
                overdueUnsentSeconds = LANE_A_OVERDUE_OVERRIDE_SECONDS - 1,
            )
        )
    }

    @Test
    fun `lane A gate block still wins over the overdue override`() {
        assertEquals(
            LaneARunDecision.SKIP_GATE_BLOCKED,
            decideLaneARun(
                nowEpochSeconds = 1000,
                nextEstimatedDueEpochSeconds = 1000,
                privacyBufferSeconds = 600,
                isGateBlocked = true,
                overdueUnsentSeconds = 10_000,
            )
        )
    }

    // ── laneAReArmDelay ────────────────────────────────────────────────────────

    @Test
    fun `lane A re-arm after past-due skip never goes negative`() {
        val d = laneAReArmDelay(
            decision = LaneARunDecision.SKIP_NEAR_DUE,
            nowEpochSeconds = 2000,
            nextEstimatedDueEpochSeconds = 1500,
            privacyBufferSeconds = 300,
            cadenceSeconds = 3600,
            jitterSeconds = 600,
            random = Random(1),
        )
        assertTrue(d >= 60.seconds, "hot-loop guard (spec M5): expected >= 60s but was $d")
    }

    @Test
    fun `lane A re-arm for SKIP_NEAR_DUE uses max of remaining window and 60s`() {
        // nextDue=2000, buffer=300, now=1600 → remaining = 2000+300-1600 = 700s
        val d = laneAReArmDelay(
            decision = LaneARunDecision.SKIP_NEAR_DUE,
            nowEpochSeconds = 1600,
            nextEstimatedDueEpochSeconds = 2000,
            privacyBufferSeconds = 300,
            cadenceSeconds = 3600,
            jitterSeconds = 600,
            random = Random(1),
        )
        assertEquals(700.seconds, d)
    }

    @Test
    fun `lane A re-arm for RUN uses cadence plus jitter`() {
        val cadence = 3600L
        val jitter = 600L
        val d = laneAReArmDelay(
            decision = LaneARunDecision.RUN,
            nowEpochSeconds = 1000,
            nextEstimatedDueEpochSeconds = 5000,
            privacyBufferSeconds = 600,
            cadenceSeconds = cadence,
            jitterSeconds = jitter,
            random = Random(42),
        )
        assertTrue(d >= (cadence - jitter).seconds, "re-arm must be >= cadence-jitter: was $d")
        assertTrue(d <= (cadence + jitter).seconds, "re-arm must be <= cadence+jitter: was $d")
    }

    @Test
    fun `lane A re-arm for SKIP_GATE_BLOCKED uses cadence plus jitter`() {
        val cadence = 3600L
        val jitter = 600L
        val d = laneAReArmDelay(
            decision = LaneARunDecision.SKIP_GATE_BLOCKED,
            nowEpochSeconds = 1000,
            nextEstimatedDueEpochSeconds = null,
            privacyBufferSeconds = 600,
            cadenceSeconds = cadence,
            jitterSeconds = jitter,
            random = Random(42),
        )
        assertTrue(d >= (cadence - jitter).seconds, "gate-blocked re-arm must be >= cadence-jitter: was $d")
        assertTrue(d <= (cadence + jitter).seconds, "gate-blocked re-arm must be <= cadence+jitter: was $d")
    }

    // ── laneAPlanDrivenReArmDelay ──────────────────────────────────────────────

    @Test
    fun `plan-driven re-arm targets nextDue minus lead`() {
        // due in 1000s, lead = 180 buffer + 120 headroom = 300 → target 700s (within [60, 3600])
        val d = laneAPlanDrivenReArmDelay(
            decision = LaneARunDecision.RUN,
            nowEpochSeconds = 10_000,
            nextEstimatedDueEpochSeconds = 11_000,
            privacyBufferSeconds = 180,
            cadenceSeconds = 3600,
            jitterSeconds = 600,
            random = Random(1),
        )
        assertEquals(700.seconds, d)
    }

    @Test
    fun `plan-driven re-arm floors at 60s when already inside the lead`() {
        val d = laneAPlanDrivenReArmDelay(
            decision = LaneARunDecision.RUN_OVERDUE_UNSENT,
            nowEpochSeconds = 10_000,
            nextEstimatedDueEpochSeconds = 10_000,
            privacyBufferSeconds = 180,
            cadenceSeconds = 3600,
            jitterSeconds = 600,
            random = Random(1),
        )
        assertEquals(60.seconds, d)
    }

    @Test
    fun `plan-driven re-arm caps at cadence for a distant window`() {
        val d = laneAPlanDrivenReArmDelay(
            decision = LaneARunDecision.RUN,
            nowEpochSeconds = 10_000,
            nextEstimatedDueEpochSeconds = 100_000,
            privacyBufferSeconds = 180,
            cadenceSeconds = 300,
            jitterSeconds = 600,
            random = Random(1),
        )
        assertEquals(300.seconds, d)
    }

    @Test
    fun `plan-driven re-arm falls back to cadence when no due estimate exists`() {
        val d = laneAPlanDrivenReArmDelay(
            decision = LaneARunDecision.RUN,
            nowEpochSeconds = 10_000,
            nextEstimatedDueEpochSeconds = null,
            privacyBufferSeconds = 180,
            cadenceSeconds = 3600,
            jitterSeconds = 600,
            random = Random(7),
        )
        assertTrue(d >= (3600 - 600).seconds && d <= (3600 + 600).seconds, "cadence±jitter expected: was $d")
    }

    // ── nextEstimatedDueEpochSeconds ───────────────────────────────────────────

    @Test
    fun `nextEstimatedDue returns null when states has no pending transfers`() {
        // All isSent=true → no pending → null
        val states = cash.z.ecc.android.sdk.MigrationTransferStates(
            transfers = listOf(
                cash.z.ecc.android.sdk.MigrationTransferState("t1", isSent = true, scheduledHeight = 100L),
            ),
            tipHeight = 90L,
        )
        assertNull(nextEstimatedDueEpochSeconds(states, est = 90L))
    }

    @Test
    fun `nextEstimatedDue returns null when estimator is sentinel -1`() {
        val states = cash.z.ecc.android.sdk.MigrationTransferStates(
            transfers = listOf(
                cash.z.ecc.android.sdk.MigrationTransferState("t1", isSent = false, scheduledHeight = 200L),
            ),
            tipHeight = 100L,
        )
        assertNull(nextEstimatedDueEpochSeconds(states, est = -1L))
    }

    @Test
    fun `nextEstimatedDue returns null when transfers list is empty`() {
        val states = cash.z.ecc.android.sdk.MigrationTransferStates(
            transfers = emptyList(),
            tipHeight = 100L,
        )
        assertNull(nextEstimatedDueEpochSeconds(states, est = 100L))
    }

    @Test
    fun `nextEstimatedDue computes min over pending transfers`() {
        val now = 1_000_000L
        val est = 800_000L
        // transfer1: scheduledHeight=800_010 → 10 blocks remaining → 10*75=750s
        // transfer2: scheduledHeight=800_100 → 100 blocks remaining → 100*75=7500s
        // min = now + 750
        val states = cash.z.ecc.android.sdk.MigrationTransferStates(
            transfers = listOf(
                cash.z.ecc.android.sdk.MigrationTransferState("t1", isSent = false, scheduledHeight = 800_010L),
                cash.z.ecc.android.sdk.MigrationTransferState("t2", isSent = false, scheduledHeight = 800_100L),
            ),
            tipHeight = est,
        )
        val result = nextEstimatedDueEpochSeconds(states, est = est, nowEpochSeconds = now)
        assertNotNull(result)
        assertEquals(now + 750L, result)
    }

    @Test
    fun `nextEstimatedDue clamps negative block difference to zero`() {
        val now = 1_000_000L
        val est = 800_100L
        // scheduledHeight=800_000 < est → blocks remaining = coerceAtLeast(0) = 0 → offset=0
        val states = cash.z.ecc.android.sdk.MigrationTransferStates(
            transfers = listOf(
                cash.z.ecc.android.sdk.MigrationTransferState("t1", isSent = false, scheduledHeight = 800_000L),
            ),
            tipHeight = est,
        )
        val result = nextEstimatedDueEpochSeconds(states, est = est, nowEpochSeconds = now)
        assertNotNull(result)
        assertEquals(now + 0L, result)
    }

    @Test
    fun `nextEstimatedDue ignores already-sent transfers`() {
        val now = 1_000_000L
        val est = 800_000L
        // t1 is sent (ignore), t2 is pending with scheduledHeight=800_020 → 20*75=1500s
        val states = cash.z.ecc.android.sdk.MigrationTransferStates(
            transfers = listOf(
                cash.z.ecc.android.sdk.MigrationTransferState("t1", isSent = true, scheduledHeight = 800_001L),
                cash.z.ecc.android.sdk.MigrationTransferState("t2", isSent = false, scheduledHeight = 800_020L),
            ),
            tipHeight = est,
        )
        val result = nextEstimatedDueEpochSeconds(states, est = est, nowEpochSeconds = now)
        assertNotNull(result)
        assertEquals(now + 1500L, result)
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
        assertTrue(shouldLaneAStop(MigrationState.RequiresAttention(AttentionReason.InvalidTransfer("t1"))))
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
