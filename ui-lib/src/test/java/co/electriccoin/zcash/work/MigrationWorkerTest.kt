package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.TransferResult
import kotlinx.coroutines.flow.flowOf
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
    fun `isSyncBurstTerminal treats a synced synchronizer as terminal`() {
        assertTrue(isSyncBurstTerminal(Synchronizer.Status.SYNCED))
    }

    @Test
    fun `isSyncBurstTerminal treats a disconnected synchronizer as terminal`() {
        assertTrue(isSyncBurstTerminal(Synchronizer.Status.DISCONNECTED))
    }

    @Test
    fun `isSyncBurstTerminal treats a stopped synchronizer as terminal`() {
        assertTrue(isSyncBurstTerminal(Synchronizer.Status.STOPPED))
    }

    @Test
    fun `isSyncBurstTerminal treats a null status (gate closed - synchronizer torn down) as terminal`() {
        // Once the tip reaches the next transfer's height, isSyncBlocked() flips true and
        // WalletCoordinator emits a null synchronizer. That null is the signal the burst is done.
        assertTrue(isSyncBurstTerminal(null))
    }

    @Test
    fun `isSyncBurstTerminal keeps waiting while still syncing`() {
        assertFalse(isSyncBurstTerminal(Synchronizer.Status.SYNCING))
    }

    @Test
    fun `isSyncBurstTerminal keeps waiting while initializing`() {
        assertFalse(isSyncBurstTerminal(Synchronizer.Status.INITIALIZING))
    }

    @Test
    fun `awaitSyncBurst returns once syncing reaches SYNCED`() = runTest {
        val terminal = awaitSyncBurst(
            flowOf(Synchronizer.Status.SYNCING, Synchronizer.Status.SYNCING, Synchronizer.Status.SYNCED)
        )

        assertEquals(Synchronizer.Status.SYNCED, terminal)
    }

    @Test
    fun `awaitSyncBurst returns when the synchronizer is torn down (null) mid-sync`() = runTest {
        val terminal = awaitSyncBurst(flowOf(Synchronizer.Status.SYNCING, null))

        assertEquals(null, terminal)
    }

    @Test
    fun `awaitSyncBurst returns on disconnect`() = runTest {
        val terminal = awaitSyncBurst(flowOf(Synchronizer.Status.SYNCING, Synchronizer.Status.DISCONNECTED))

        assertEquals(Synchronizer.Status.DISCONNECTED, terminal)
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
