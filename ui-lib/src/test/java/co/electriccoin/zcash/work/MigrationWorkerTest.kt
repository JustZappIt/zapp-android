package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MigrationWorkerTest {

    // ── executeWithRetries ────────────────────────────────────────────────────

    @Test
    fun `a Success on the first attempt does not retry`() = runTest {
        var callCount = 0
        val result = executeWithRetries(retryDelayMs = 0) {
            callCount++
            TransferAttemptOutcome.Executed(TransferResult.Success("txid"))
        }

        assertIs<TransferAttemptOutcome.Executed>(result)
        assertIs<TransferResult.Success>(result.result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a retryable NetworkError retries up to maxAttempts then stops`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = true))
        }

        assertIs<TransferAttemptOutcome.Executed>(result)
        assertIs<TransferResult.NetworkError>(result.result)
        assertEquals(3, callCount)
    }

    @Test
    fun `a non-retryable NetworkError stops immediately without exhausting maxAttempts`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = false))
        }

        assertIs<TransferAttemptOutcome.Executed>(result)
        assertIs<TransferResult.NetworkError>(result.result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a NothingDue result stops immediately without retrying`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferAttemptOutcome.NothingDue
        }

        assertIs<TransferAttemptOutcome.NothingDue>(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `an AwaitingProof result stops immediately without retrying`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferAttemptOutcome.AwaitingProof(1L)
        }

        assertIs<TransferAttemptOutcome.AwaitingProof>(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a retryable NetworkError that later succeeds stops as soon as it succeeds`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            if (callCount < 2) {
                TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = true))
            } else {
                TransferAttemptOutcome.Executed(TransferResult.Success("txid"))
            }
        }

        assertIs<TransferAttemptOutcome.Executed>(result)
        assertIs<TransferResult.Success>(result.result)
        assertEquals(2, callCount)
    }

    // ── decideLaneBPreflight ──────────────────────────────────────────────────

    @Test
    fun `lane B defers while lane A is running`() {
        assertEquals(
            LaneBAction.DEFER_OVERLAP,
            decideLaneBPreflight(
                laneARunning = true,
                synchronizerSyncing = false,
                nowEpochSeconds = 1000,
                lastNetworkActivityEpochSeconds = 0,
                privacyBufferSeconds = 600,
            )
        )
    }

    @Test
    fun `lane B defers inside the quiet gap`() {
        assertEquals(
            LaneBAction.DEFER_OVERLAP,
            decideLaneBPreflight(
                laneARunning = false,
                synchronizerSyncing = false,
                nowEpochSeconds = 1000,
                lastNetworkActivityEpochSeconds = 700,
                privacyBufferSeconds = 600,
            )
        )
    }

    @Test
    fun `lane B proceeds when all sources quiet past the gap`() {
        assertEquals(
            LaneBAction.BROADCAST,
            decideLaneBPreflight(false, false, 1000, 100, 600)
        )
    }

    @Test
    fun `lane B proceeds when no sync ever happened`() {
        assertEquals(
            LaneBAction.BROADCAST,
            decideLaneBPreflight(false, false, 1000, null, 600)
        )
    }

    @Test
    fun `lane B defers when synchronizer is syncing even if lane A is not running`() {
        assertEquals(
            LaneBAction.DEFER_OVERLAP,
            decideLaneBPreflight(
                laneARunning = false,
                synchronizerSyncing = true,
                nowEpochSeconds = 1000,
                lastNetworkActivityEpochSeconds = null,
                privacyBufferSeconds = 600,
            )
        )
    }

    @Test
    fun `lane B proceeds when gap exactly elapsed`() {
        // now=1000, last=400, buffer=600 → gap=600, exactly elapsed → BROADCAST
        assertEquals(
            LaneBAction.BROADCAST,
            decideLaneBPreflight(false, false, 1000, 400, 600)
        )
    }

    // ── syncCompletedSince ────────────────────────────────────────────────────

    @Test
    fun `syncCompletedSince returns false when lastActivity is null`() {
        assertFalse(syncCompletedSince(lastActivity = null, lastShift = null))
        assertFalse(syncCompletedSince(lastActivity = null, lastShift = Instant.ofEpochSecond(500)))
    }

    @Test
    fun `syncCompletedSince returns true when lastActivity is non-null and lastShift is null`() {
        // Null shift → treat as EPOCH; any activity is "since" then.
        assertTrue(syncCompletedSince(lastActivity = Instant.ofEpochSecond(1000), lastShift = null))
    }

    @Test
    fun `syncCompletedSince returns true when lastActivity is after lastShift`() {
        assertTrue(
            syncCompletedSince(
                lastActivity = Instant.ofEpochSecond(1000),
                lastShift = Instant.ofEpochSecond(500),
            )
        )
    }

    @Test
    fun `syncCompletedSince returns false when lastActivity is before lastShift`() {
        assertFalse(
            syncCompletedSince(
                lastActivity = Instant.ofEpochSecond(400),
                lastShift = Instant.ofEpochSecond(500),
            )
        )
    }

    @Test
    fun `syncCompletedSince returns false when lastActivity equals lastShift`() {
        // Strictly after — equal timestamps mean no NEW activity since the shift.
        assertFalse(
            syncCompletedSince(
                lastActivity = Instant.ofEpochSecond(500),
                lastShift = Instant.ofEpochSecond(500),
            )
        )
    }

    // ── shouldEscalateShift (F4) ──────────────────────────────────────────────
    // Escalation fires only on the TRANSITION to the 3rd counted shift: syncSince && count == 3.

    @Test
    fun `escalation fires only on the counted 3rd shift with a completed sync`() {
        // count < threshold → never, regardless of syncSince.
        assertFalse(shouldEscalateShift(syncSince = true, count = 1))
        assertFalse(shouldEscalateShift(syncSince = true, count = 2))
        assertFalse(shouldEscalateShift(syncSince = false, count = 2))

        // The exact transition — sync completed AND count reached the threshold.
        assertTrue(shouldEscalateShift(syncSince = true, count = SHIFT_ESCALATION_THRESHOLD))
    }

    @Test
    fun `escalation does not re-fire on a no-sync shift that leaves count at threshold`() {
        // count stays at 3 on subsequent no-sync shifts; without the syncSince gate this would
        // re-fire the once-only notification every run. The gate blocks it.
        assertFalse(shouldEscalateShift(syncSince = false, count = SHIFT_ESCALATION_THRESHOLD))
    }

    @Test
    fun `escalation does not fire past the threshold`() {
        // Counter never exceeds 3 in practice, but guard the equality boundary anyway.
        assertFalse(shouldEscalateShift(syncSince = true, count = SHIFT_ESCALATION_THRESHOLD + 1))
    }
}
