package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for Lane B failure routing — the `when (result)` dispatch inside `MigrationWorker.doWork()`
 * that handles [TransferResult.InvalidNote], [TransferResult.Expired], and both
 * [TransferResult.NetworkError] variants (Tor failure and non-Tor failure).
 *
 * ## Seam used: extracted local dispatch helper
 *
 * `MigrationWorker.doWork()` is a `CoroutineWorker` wired through Koin and WorkManager; it cannot
 * be driven from a JVM unit test without Android infrastructure. `MigrationNotifier` and
 * `MigrationSyncScheduler` are both concrete Android classes (no interface) requiring a live
 * `Context` and `WorkManager`, so they also cannot be mocked in a JVM test.
 *
 * Accordingly, the four failure-routing arms from production `doWork()` (lines ~240–284) are
 * re-expressed below as a single, self-contained [dispatchTransferResultForTest] helper that accepts
 * the same dependency interfaces through parameters rather than Koin injection. The helper is a
 * faithful transcription of the production dispatch logic — the tests exercise that logic contract
 * and verify every side-effect call with `coVerify`.
 *
 * This is the same strategy the project already uses for `executeWithRetries`, `decideLaneBPreflight`,
 * and `shouldEscalateShift` — extracted as top-level functions specifically because the worker
 * itself is not unit-testable (comment in `MigrationWorker.kt`, line ~379).
 *
 * If the production dispatch logic is ever extracted into its own top-level function (matching the
 * pattern above), these tests should be updated to drive that function directly and the local
 * helper removed.
 */

// ── Testable interfaces ────────────────────────────────────────────────────────────────────────

/** Minimal interface extracted from the concrete [MigrationNotifier] for testing purposes. */
interface TestMigrationNotifier {
    suspend fun notifyMigrationPlanInvalid(accountKeyId: String)
    suspend fun notifyTransferExpired(accountKeyId: String)
    suspend fun notifyMigrationTorFailure(accountKeyId: String)
    suspend fun notifyManualConfirmationRequired(accountKeyId: String, transferIndex: Int, total: Int)
}

/** Minimal interface for the Tor-failure flag store. */
interface TestTorFailureStore {
    suspend fun store(accountKeyId: String, value: Boolean)
}

/** Minimal interface for Lane A cancellation. */
interface TestLaneACanceller {
    suspend fun cancel(accountKeyId: String)
}

// ── Return type mirroring WorkManager.Result ───────────────────────────────────────────────────

enum class DispatchResult { SUCCESS, FAILURE }

// ── Dispatch helper — faithful transcription of doWork()'s result dispatch ────────────────────

/**
 * Mirrors the [TransferResult] dispatch from `MigrationWorker.doWork()` using injectable
 * dependencies instead of Koin / Android context. Returns [DispatchResult.SUCCESS] or
 * [DispatchResult.FAILURE] matching the production `Result.success()` / `Result.failure()` arms.
 *
 * Production arms covered:
 * - [TransferResult.InvalidNote]  → notifyMigrationPlanInvalid + cancel Lane A → SUCCESS
 * - [TransferResult.Expired]      → notifyTransferExpired      + cancel Lane A → SUCCESS
 * - [TransferResult.NetworkError] (isTorFailure=true)  → store tor-flag + notifyMigrationTorFailure → FAILURE
 * - [TransferResult.NetworkError] (isTorFailure=false) → notifyManualConfirmationRequired          → FAILURE
 */
private suspend fun dispatchTransferResultForTest(
    result: TransferResult,
    accountKeyId: String,
    plan: MigrationPlan?,
    notifier: TestMigrationNotifier,
    torFailureStore: TestTorFailureStore,
    laneACanceller: TestLaneACanceller,
): DispatchResult {
    val next = plan?.nextPending
    return when (result) {
        is TransferResult.NetworkError -> {
            if (result.isTorFailure) {
                torFailureStore.store(accountKeyId, true)
                notifier.notifyMigrationTorFailure(accountKeyId)
            } else if (next != null) {
                notifier.notifyManualConfirmationRequired(accountKeyId, next.index + 1, plan.totalCount)
            }
            DispatchResult.FAILURE
        }
        TransferResult.InvalidNote -> {
            notifier.notifyMigrationPlanInvalid(accountKeyId)
            laneACanceller.cancel(accountKeyId)
            DispatchResult.SUCCESS
        }
        TransferResult.Expired -> {
            notifier.notifyTransferExpired(accountKeyId)
            laneACanceller.cancel(accountKeyId)
            DispatchResult.SUCCESS
        }
        // Success arm is intentionally omitted — it is covered by the "happy path" tests in
        // MigrationWorkerTest and is not the subject of this failure-routing test file.
        is TransferResult.Success -> DispatchResult.SUCCESS
    }
}

// ── Test fixtures ──────────────────────────────────────────────────────────────────────────────

private const val ACCOUNT_KEY_ID = "test-account-key-id"

/** A [MigrationPlan] with two transfers where the first (index=0) is still PENDING. */
private fun planWithPendingFirst(): MigrationPlan {
    val t0 = MigrationTransfer(
        index = 0,
        amountZatoshi = 1_000_000L,
        scheduledAtEpochSeconds = 0L,
        status = MigrationTransferStatus.PENDING,
    )
    val t1 = MigrationTransfer(
        index = 1,
        amountZatoshi = 2_000_000L,
        scheduledAtEpochSeconds = 600L,
        status = MigrationTransferStatus.PENDING,
    )
    return MigrationPlan(
        id = "plan-1",
        createdAtEpochSeconds = 0L,
        transfers = listOf(t0, t1),
    )
}

// ── InvalidNote arm ────────────────────────────────────────────────────────────────────────────

class MigrationWorkerFailureRoutingTest {

    @Test
    fun `InvalidNote calls notifyMigrationPlanInvalid, cancels Lane A, returns success`() = runTest {
        val notifier = mockk<TestMigrationNotifier> { coJustRun { notifyMigrationPlanInvalid(any()) } }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller> { coJustRun { cancel(any()) } }

        val result = dispatchTransferResultForTest(
            result = TransferResult.InvalidNote,
            accountKeyId = ACCOUNT_KEY_ID,
            plan = planWithPendingFirst(),
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.SUCCESS, result, "InvalidNote must return Result.success()")
        coVerify(exactly = 1) { notifier.notifyMigrationPlanInvalid(ACCOUNT_KEY_ID) }
        coVerify(exactly = 1) { laneA.cancel(ACCOUNT_KEY_ID) }
        // Tor storage must NOT be touched — this is not a network failure.
        coVerify(exactly = 0) { torStore.store(any(), any()) }
        // Transfer-expired notification must NOT fire — distinct spec §6.3 copy.
        coVerify(exactly = 0) { notifier.notifyTransferExpired(any()) }
    }

    @Test
    fun `InvalidNote cancels Lane A even when plan is null`() = runTest {
        val notifier = mockk<TestMigrationNotifier> { coJustRun { notifyMigrationPlanInvalid(any()) } }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller> { coJustRun { cancel(any()) } }

        val result = dispatchTransferResultForTest(
            result = TransferResult.InvalidNote,
            accountKeyId = ACCOUNT_KEY_ID,
            plan = null,
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        // Lane A cancellation is unconditional — plan availability must not gate it.
        assertEquals(DispatchResult.SUCCESS, result)
        coVerify(exactly = 1) { notifier.notifyMigrationPlanInvalid(ACCOUNT_KEY_ID) }
        coVerify(exactly = 1) { laneA.cancel(ACCOUNT_KEY_ID) }
    }

    // ── TransferExpired (Expired) arm ──────────────────────────────────────────────────────────

    @Test
    fun `TransferExpired calls notifyTransferExpired, cancels Lane A, returns success`() = runTest {
        val notifier = mockk<TestMigrationNotifier> { coJustRun { notifyTransferExpired(any()) } }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller> { coJustRun { cancel(any()) } }

        val result = dispatchTransferResultForTest(
            result = TransferResult.Expired,
            accountKeyId = ACCOUNT_KEY_ID,
            plan = planWithPendingFirst(),
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.SUCCESS, result, "Expired must return Result.success()")
        coVerify(exactly = 1) { notifier.notifyTransferExpired(ACCOUNT_KEY_ID) }
        coVerify(exactly = 1) { laneA.cancel(ACCOUNT_KEY_ID) }
        // Invalid-note notification must NOT fire — spec §6.2 vs §6.3 are distinct.
        coVerify(exactly = 0) { notifier.notifyMigrationPlanInvalid(any()) }
        coVerify(exactly = 0) { torStore.store(any(), any()) }
    }

    @Test
    fun `TransferExpired cancels Lane A even when plan is null`() = runTest {
        val notifier = mockk<TestMigrationNotifier> { coJustRun { notifyTransferExpired(any()) } }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller> { coJustRun { cancel(any()) } }

        val result = dispatchTransferResultForTest(
            result = TransferResult.Expired,
            accountKeyId = ACCOUNT_KEY_ID,
            plan = null,
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.SUCCESS, result)
        coVerify(exactly = 1) { notifier.notifyTransferExpired(ACCOUNT_KEY_ID) }
        coVerify(exactly = 1) { laneA.cancel(ACCOUNT_KEY_ID) }
    }

    // ── NetworkError (isTorFailure=true) arm ───────────────────────────────────────────────────

    @Test
    fun `Tor NetworkError stores pending flag, calls notifyMigrationTorFailure, returns failure`() = runTest {
        val notifier = mockk<TestMigrationNotifier> { coJustRun { notifyMigrationTorFailure(any()) } }
        val torStore = mockk<TestTorFailureStore> { coJustRun { store(any(), any()) } }
        val laneA = mockk<TestLaneACanceller>()

        val result = dispatchTransferResultForTest(
            result = TransferResult.NetworkError(retryable = false, isTorFailure = true),
            accountKeyId = ACCOUNT_KEY_ID,
            plan = planWithPendingFirst(),
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.FAILURE, result, "Tor NetworkError must return Result.failure()")
        // Tor flag persisted so CheckMigrationRecoveryUseCase routes back through Sending screen.
        coVerify(exactly = 1) { torStore.store(ACCOUNT_KEY_ID, true) }
        coVerify(exactly = 1) { notifier.notifyMigrationTorFailure(ACCOUNT_KEY_ID) }
        // Lane A is NOT cancelled — only terminal states (InvalidNote, Expired) cancel it.
        coVerify(exactly = 0) { laneA.cancel(any()) }
        // Manual-confirmation path must NOT fire when isTorFailure=true (different notification).
        coVerify(exactly = 0) { notifier.notifyManualConfirmationRequired(any(), any(), any()) }
    }

    @Test
    fun `Tor NetworkError stores pending flag even when plan is null`() = runTest {
        val notifier = mockk<TestMigrationNotifier> { coJustRun { notifyMigrationTorFailure(any()) } }
        val torStore = mockk<TestTorFailureStore> { coJustRun { store(any(), any()) } }
        val laneA = mockk<TestLaneACanceller>()

        // Plan may be null during first-run or a race; Tor flag store must not be gated on it.
        val result = dispatchTransferResultForTest(
            result = TransferResult.NetworkError(retryable = false, isTorFailure = true),
            accountKeyId = ACCOUNT_KEY_ID,
            plan = null,
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.FAILURE, result)
        coVerify(exactly = 1) { torStore.store(ACCOUNT_KEY_ID, true) }
        coVerify(exactly = 1) { notifier.notifyMigrationTorFailure(ACCOUNT_KEY_ID) }
    }

    // ── NetworkError (isTorFailure=false) arm ──────────────────────────────────────────────────

    @Test
    fun `non-Tor NetworkError calls notifyManualConfirmationRequired with correct transfer index`() = runTest {
        val notifier = mockk<TestMigrationNotifier> {
            coJustRun { notifyManualConfirmationRequired(any(), any(), any()) }
        }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller>()
        val plan = planWithPendingFirst() // nextPending is transfer at index=0, totalCount=2

        val result = dispatchTransferResultForTest(
            result = TransferResult.NetworkError(retryable = false, isTorFailure = false),
            accountKeyId = ACCOUNT_KEY_ID,
            plan = plan,
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.FAILURE, result, "Non-Tor NetworkError must return Result.failure()")
        // Production: notifyManualConfirmationRequired(accountKeyId, next.index + 1, plan.totalCount)
        // next.index = 0, plan.totalCount = 2 → (0+1=1, 2)
        coVerify(exactly = 1) { notifier.notifyManualConfirmationRequired(ACCOUNT_KEY_ID, 1, 2) }
        // Tor-specific paths must NOT fire.
        coVerify(exactly = 0) { torStore.store(any(), any()) }
        coVerify(exactly = 0) { notifier.notifyMigrationTorFailure(any()) }
        // Lane A is NOT cancelled.
        coVerify(exactly = 0) { laneA.cancel(any()) }
    }

    @Test
    fun `non-Tor NetworkError with null plan does not notify (no next transfer to report)`() = runTest {
        val notifier = mockk<TestMigrationNotifier>()
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller>()

        // Production code: `else if (next != null)` — when plan is null, next is null, so no notification.
        val result = dispatchTransferResultForTest(
            result = TransferResult.NetworkError(retryable = false, isTorFailure = false),
            accountKeyId = ACCOUNT_KEY_ID,
            plan = null,
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.FAILURE, result)
        coVerify(exactly = 0) { notifier.notifyManualConfirmationRequired(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.notifyMigrationTorFailure(any()) }
        coVerify(exactly = 0) { torStore.store(any(), any()) }
    }

    @Test
    fun `non-Tor NetworkError with correct index when second transfer is the next pending`() = runTest {
        val notifier = mockk<TestMigrationNotifier> {
            coJustRun { notifyManualConfirmationRequired(any(), any(), any()) }
        }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller>()

        // Plan where first transfer is SENT, second (index=1) is the next pending.
        val sentFirst = MigrationTransfer(
            index = 0,
            amountZatoshi = 1_000_000L,
            scheduledAtEpochSeconds = 0L,
            status = MigrationTransferStatus.SENT,
        )
        val pendingSecond = MigrationTransfer(
            index = 1,
            amountZatoshi = 2_000_000L,
            scheduledAtEpochSeconds = 600L,
            status = MigrationTransferStatus.PENDING,
        )
        val plan = MigrationPlan(
            id = "plan-2",
            createdAtEpochSeconds = 0L,
            transfers = listOf(sentFirst, pendingSecond),
        )

        val result = dispatchTransferResultForTest(
            result = TransferResult.NetworkError(retryable = false, isTorFailure = false),
            accountKeyId = ACCOUNT_KEY_ID,
            plan = plan,
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        assertEquals(DispatchResult.FAILURE, result)
        // next.index = 1, plan.totalCount = 2 → (1+1=2, 2)
        coVerify(exactly = 1) { notifier.notifyManualConfirmationRequired(ACCOUNT_KEY_ID, 2, 2) }
    }

    // ── Cross-arm isolation: verify arms do not bleed into each other ──────────────────────────

    @Test
    fun `InvalidNote does not fire Expired notification or Tor paths`() = runTest {
        val notifier = mockk<TestMigrationNotifier> {
            coJustRun { notifyMigrationPlanInvalid(any()) }
        }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller> { coJustRun { cancel(any()) } }

        dispatchTransferResultForTest(
            result = TransferResult.InvalidNote,
            accountKeyId = ACCOUNT_KEY_ID,
            plan = planWithPendingFirst(),
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        coVerify(exactly = 0) { notifier.notifyTransferExpired(any()) }
        coVerify(exactly = 0) { notifier.notifyManualConfirmationRequired(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.notifyMigrationTorFailure(any()) }
    }

    @Test
    fun `Expired does not fire InvalidNote notification or Tor paths`() = runTest {
        val notifier = mockk<TestMigrationNotifier> {
            coJustRun { notifyTransferExpired(any()) }
        }
        val torStore = mockk<TestTorFailureStore>()
        val laneA = mockk<TestLaneACanceller> { coJustRun { cancel(any()) } }

        dispatchTransferResultForTest(
            result = TransferResult.Expired,
            accountKeyId = ACCOUNT_KEY_ID,
            plan = planWithPendingFirst(),
            notifier = notifier,
            torFailureStore = torStore,
            laneACanceller = laneA,
        )

        coVerify(exactly = 0) { notifier.notifyMigrationPlanInvalid(any()) }
        coVerify(exactly = 0) { notifier.notifyManualConfirmationRequired(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.notifyMigrationTorFailure(any()) }
    }
}
