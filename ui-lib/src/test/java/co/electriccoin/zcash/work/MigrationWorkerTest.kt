package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.TransferResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class MigrationWorkerTest {
    @Test
    fun `a Success on the first attempt does not retry`() = runTest {
        var callCount = 0
        val result = executeWithRetries(retryDelayMs = 0) {
            callCount++
            TransferResult.Success("txid")
        }

        assertIs<TransferResult.Success>(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a retryable NetworkError retries up to maxAttempts then stops`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferResult.NetworkError(retryable = true)
        }

        assertIs<TransferResult.NetworkError>(result)
        assertEquals(3, callCount)
    }

    @Test
    fun `a non-retryable NetworkError stops immediately without exhausting maxAttempts`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferResult.NetworkError(retryable = false)
        }

        assertIs<TransferResult.NetworkError>(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a null result (nothing due yet) stops immediately without retrying`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            null
        }

        assertEquals(null, result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a retryable NetworkError that later succeeds stops as soon as it succeeds`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            if (callCount < 2) TransferResult.NetworkError(retryable = true) else TransferResult.Success("txid")
        }

        assertIs<TransferResult.Success>(result)
        assertEquals(2, callCount)
    }

    @Test
    fun `decideNullResultAction waits and retries when a transfer is pending but not yet overdue`() {
        val action = decideNullResultAction(hasNextPending = true, isOverdue = false)

        assertEquals(NullResultAction.WAIT_AND_RETRY, action)
    }

    @Test
    fun `decideNullResultAction hands off to the app once confirmed overdue`() {
        val action = decideNullResultAction(hasNextPending = true, isOverdue = true)

        assertEquals(NullResultAction.HANDOFF_TO_APP, action)
    }

    @Test
    fun `decideNullResultAction does nothing when there is no pending transfer at all`() {
        // hasNextPending=false takes priority over isOverdue=true — there's nothing to be
        // "overdue" about if there's no pending transfer at all.
        val action = decideNullResultAction(hasNextPending = false, isOverdue = true)

        assertEquals(NullResultAction.NOTHING_PENDING, action)
    }

    // ── Background sync-advance ─────────────────────────────────────────────────

    @Test
    fun `isBroadcastableAfterBurst is true when the burst reached the target`() {
        // The burst advanced the tip until the migration height gate confirmed the transfer.
        assertTrue(isBroadcastableAfterBurst(Synchronizer.SyncBurstResult.TARGET_REACHED, hasOverdueNow = false))
    }

    @Test
    fun `isBroadcastableAfterBurst is true when the transfer is overdue even if the burst did not report it`() {
        // The gate flipped just after the burst returned a non-target terminal; the fresh
        // hasOverdueTransfers() read still catches it.
        assertTrue(isBroadcastableAfterBurst(Synchronizer.SyncBurstResult.SYNCED_TO_TIP, hasOverdueNow = true))
    }

    @Test
    fun `isBroadcastableAfterBurst is false when the burst made no progress and nothing is overdue`() {
        assertFalse(isBroadcastableAfterBurst(Synchronizer.SyncBurstResult.TIMEOUT, hasOverdueNow = false))
    }

    @Test
    fun `rescheduleDelayAfterSyncBurst waits a full privacy buffer once the transfer is broadcastable`() {
        // The burst advanced the tip and the transfer is now overdue/broadcastable. Wait a full
        // buffer before the next run broadcasts, so the sync burst and the broadcast are decoupled.
        val delay = rescheduleDelayAfterSyncBurst(
            isNowOverdue = true,
            privacyBuffer = 10.minutes,
            retryInterval = 75_000.milliseconds,
        )

        assertEquals(10.minutes, delay)
    }

    @Test
    fun `rescheduleDelayAfterSyncBurst falls back to the short retry when still not broadcastable`() {
        // Tip still short (or proof not witnessed) — nothing to decouple yet, so retry soon.
        val delay = rescheduleDelayAfterSyncBurst(
            isNowOverdue = false,
            privacyBuffer = 10.minutes,
            retryInterval = 75_000.milliseconds,
        )

        assertEquals(75_000.milliseconds, delay)
    }
}
