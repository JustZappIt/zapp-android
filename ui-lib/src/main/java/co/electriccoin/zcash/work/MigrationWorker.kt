package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.withLiveStatusOnly
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.MigrationShiftCounterStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Keep
class MigrationWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters), KoinComponent {

    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase by inject()
    private val migrationPlanRepository: MigrationPlanRepository by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider by inject()
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider by inject()
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val lastNetworkActivity: LastNetworkActivityStorageProvider by inject()
    private val shiftCounter: MigrationShiftCounterStorageProvider by inject()

    override suspend fun doWork(): Result {
        val accountKeyId = inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID)
            ?: getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId().also {
                Twig.warn { "MIGRATION_DIAG MigrationWorker: no accountKeyId in inputData — falling back to selected account $it (pre-upgrade job)" }
            }

        val sdk = getOrchardMigrationSdk(accountKeyId) ?: run {
            Twig.debug { "MIGRATION_DIAG MigrationWorker: no SDK for account $accountKeyId — skipping." }
            return Result.success()
        }

        val laneARunning = withContext(Dispatchers.IO) {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosForUniqueWork(WorkIds.WORK_ID_MIGRATION_SYNC).get()
        }.any { it.state == WorkInfo.State.RUNNING }

        // status is a Flow<Status> — take the first emission without blocking indefinitely.
        val syncing = synchronizerProvider.synchronizer.value?.status?.first() == Synchronizer.Status.SYNCING

        val preflight = decideLaneBPreflight(
            laneARunning = laneARunning,
            synchronizerSyncing = syncing,
            nowEpochSeconds = nowEpochSeconds(),
            lastNetworkActivityEpochSeconds = lastNetworkActivity.get()?.epochSecond,
            privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
        )
        if (preflight == LaneBAction.DEFER_OVERLAP) {
            // Local delay (spec §5): engine untouched.
            MigrationScheduler(applicationContext).schedule(accountKeyId, sdk.privacySyncBufferDuration())
            return Result.success()
        }

        val plan = migrationPlanRepository.load(accountKeyId)
        val next = plan?.nextPending
        val useTor = isMigrationTorEnabledStorageProvider.get(accountKeyId)

        // Retries within this single worker invocation, same attempt count (3) as
        // MigrationSendingVM.sendOnce()'s foreground loop — but a different trigger: sendOnce()
        // retries while the result is null (still polling for readiness) and stops on any
        // non-null result, while this retries only on a retryable NetworkError and stops
        // immediately on null. So a persistent network error settles into an error state after 3
        // attempts instead of retrying via WorkManager's Result.retry() indefinitely (previously
        // observed: dumpsys jobscheduler showed the same worker restarting and running for the
        // full ~10-minute execution ceiling, repeatedly, for hours).
        return when (val outcome = executeWithRetries { sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true) }) {
            is TransferAttemptOutcome.NothingDue -> {
                // Not due yet by estimate: re-arm for the live next window (states-based, like Lane A).
                scheduleForNextLiveWindow(accountKeyId, sdk)
                Twig.debug { "MIGRATION_DIAG LaneB: NothingDue — rescheduled for next live window." }
                Result.success()
            }
            is TransferAttemptOutcome.AwaitingProof -> {
                val lastActivity: Instant? = lastNetworkActivity.get()
                val lastShift: Instant? = shiftCounter.lastShiftAt(accountKeyId)
                val syncSince = syncCompletedSince(lastActivity, lastShift)
                val count = shiftCounter.incrementIfSameTransfer(accountKeyId, outcome.transferId, syncCompletedSinceLastShift = syncSince)
                val newHeight = sdk.rescheduleUnprovenTransfer(outcome.transferId)
                if (count == SHIFT_ESCALATION_THRESHOLD) {
                    if (sdk.reconcileInvalidations()) {
                        migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                    } else {
                        // Once only — count == 3 exact equality ensures single notification.
                        migrationNotifier.notifyManualConfirmationRequired(accountKeyId, 0, 0)
                    }
                }
                scheduleForNextLiveWindow(accountKeyId, sdk)
                Twig.debug { "MIGRATION_DIAG LaneB: shifted ${outcome.transferId} to $newHeight (count=$count)" }
                Result.success()
            }
            is TransferAttemptOutcome.Executed -> when (val result = outcome.result) {
                is TransferResult.Success -> {
                    shiftCounter.reset(accountKeyId)
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer sent — txId=${result.txId}" }
                    // Fold the SDK's authoritative "sent" status back into the persisted plan so the
                    // cached completedCount/nextPending advance — the home banner and the notification
                    // below both read the raw cached plan, so without this write-through they'd report a
                    // stale count (stuck on the first transfer) forever. Keyed by the worker's own
                    // account (inputData), not the currently-selected one.
                    val updatedPlan = migrationPlanRepository.load(accountKeyId)
                        ?.withLiveStatusOnly(sdk.getMigrationTransferStates())
                        ?.also { migrationPlanRepository.save(accountKeyId, it) }
                    if (updatedPlan?.nextPending != null) {
                        val delay = nextDelay(updatedPlan)
                        MigrationScheduler(applicationContext).schedule(accountKeyId, delay)
                        migrationNotifier.notifyTransferComplete(accountKeyId, updatedPlan.completedCount, updatedPlan.totalCount)
                        Twig.debug { "MIGRATION_DIAG MigrationWorker: next transfer scheduled in $delay" }
                    } else {
                        migrationNotifier.notifyMigrationComplete(accountKeyId)
                        Twig.debug { "MIGRATION_DIAG MigrationWorker: migration complete!" }
                    }
                    Result.success()
                }
                is TransferResult.NetworkError -> {
                    // Retries already exhausted (or the failure was non-retryable) inside
                    // executeWithRetries above — settle into an error state now rather than asking
                    // WorkManager for yet another attempt.
                    Twig.debug {
                        "MIGRATION_DIAG MigrationWorker: network error after retries, isTorFailure=${result.isTorFailure}"
                    }
                    if (result.isTorFailure) {
                        // Same reasoning as MigrationSendingVM.sendOnce()'s interactive NetworkError
                        // branch. Persist a flag so app-open reconciliation
                        // (CheckMigrationRecoveryUseCase) routes back through the Sending screen
                        // instead of the generic manual-confirmation path, and surface a distinct
                        // notification so this looks different from any other missed transfer.
                        pendingMigrationTorFailureStorageProvider.store(accountKeyId, true)
                        migrationNotifier.notifyMigrationTorFailure(accountKeyId)
                    } else if (next != null) {
                        // Nothing else re-arms a future attempt for a non-retryable failure — the
                        // user must open the app and act, same as a missed/stalled window.
                        migrationNotifier.notifyManualConfirmationRequired(accountKeyId, next.index + 1, plan?.totalCount ?: 0)
                    }
                    Result.failure()
                }
                TransferResult.InvalidNote -> {
                    // State is now RequiresAttention(InvalidTransfer) — spec §6.2, notes were spent
                    // outside the migration flow. On-launch reconciliation will surface the prompt, but
                    // the user still needs telling since nothing else runs meanwhile.
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer invalid (note spent externally) — user action required on next open." }
                    migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                    Result.success()
                }
                TransferResult.Expired -> {
                    // State is now RequiresAttention(TransferExpired) — spec §6.3, the transfer's
                    // anchor expired before it could broadcast (the app wasn't opened in time). Distinct
                    // user-facing copy from InvalidNote above, even though both branches otherwise
                    // handle identically (no further action possible from the background worker).
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer expired — user action required on next open." }
                    migrationNotifier.notifyTransferExpired(accountKeyId)
                    Result.success()
                }
            }
            null -> Result.success() // executeWithRetries exhausted on retryable network errors mid-outcome
        }
    }

    /**
     * Schedules the next Lane B run based on live SDK transfer states. Reads the next pending
     * transfer's scheduledHeight from the SDK and computes a block-time-based delay; falls back to
     * the plan-repo scheduledAt estimate when the SDK has no pending states.
     */
    private suspend fun scheduleForNextLiveWindow(accountKeyId: String, sdk: OrchardMigrationSdk) {
        val states = sdk.getMigrationTransferStates()
        val est = sdk.estimatedChainTip()
        val delay: Duration = if (states != null && est >= 0L) {
            val nextScheduledHeight = states.transfers
                .filter { !it.isSent }
                .minOfOrNull { it.scheduledHeight }
            if (nextScheduledHeight != null) {
                val blocksRemaining = (nextScheduledHeight - est).coerceAtLeast(1L)
                (blocksRemaining * SECONDS_PER_BLOCK_LANE_B).seconds
            } else {
                // All transfers sent — fall through to plan-repo fallback which will also be empty.
                planRepoDerivedDelay(accountKeyId)
            }
        } else {
            planRepoDerivedDelay(accountKeyId)
        }
        MigrationScheduler(applicationContext).schedule(accountKeyId, delay)
        Twig.debug { "MIGRATION_DIAG LaneB: scheduleForNextLiveWindow — delay=$delay" }
    }

    private suspend fun planRepoDerivedDelay(accountKeyId: String): Duration {
        val plan = migrationPlanRepository.load(accountKeyId)
        val next = plan?.nextPending ?: return 60.seconds
        val remaining = next.scheduledAt - Clock.System.now()
        return if (remaining.isNegative() || remaining < 60.seconds) 60.seconds else remaining
    }

    private fun nextDelay(plan: MigrationPlan): Duration {
        val next = plan.nextPending ?: return 0.seconds
        val remaining = next.scheduledAt - Clock.System.now()
        return if (remaining.isNegative()) 0.seconds else remaining
    }
}

private const val SHIFT_ESCALATION_THRESHOLD = 3
private const val SECONDS_PER_BLOCK_LANE_B = 75L

// Same attempt count (3) as MigrationSendingVM.sendOnce()'s foreground retry loop — but not the
// same retry trigger: sendOnce() retries while polling for readiness (result == null) and stops
// on any non-null result; this retries only on a retryable NetworkError and stops on null. Each
// loop is correct for its own context (foreground polls for the transfer becoming ready;
// background rides out a flaky network) — they just happen to share the same attempt budget.
private const val MAX_BROADCAST_ATTEMPTS = 3
private const val BROADCAST_RETRY_DELAY_MS = 1500L

/**
 * Calls [attempt] up to [maxAttempts] times, retrying only while the result is an
 * [TransferAttemptOutcome.Executed] wrapping a retryable [TransferResult.NetworkError] — anything
 * else (NothingDue, AwaitingProof, a non-retryable error, success) short-circuits immediately.
 * Returns null only when [attempt] itself returns null (should not happen with the current SDK
 * contract, but guards against future changes). Top-level and `internal` (rather than a private
 * method on [MigrationWorker]) specifically so it's unit-testable without Koin or WorkManager,
 * neither of which this codebase has test infrastructure for today.
 */
internal suspend fun executeWithRetries(
    maxAttempts: Int = MAX_BROADCAST_ATTEMPTS,
    retryDelayMs: Long = BROADCAST_RETRY_DELAY_MS,
    attempt: suspend () -> TransferAttemptOutcome,
): TransferAttemptOutcome? {
    var result: TransferAttemptOutcome? = null
    for (i in 0 until maxAttempts) {
        if (i > 0) delay(retryDelayMs)
        result = attempt()
        val current = result
        val shouldRetry = current is TransferAttemptOutcome.Executed &&
            current.result is TransferResult.NetworkError &&
            (current.result as TransferResult.NetworkError).retryable
        if (!shouldRetry) break
    }
    return result
}

/**
 * What Lane B should do before calling the SDK's executeNextPendingTransfer.
 *
 * - [LaneBAction.DEFER_OVERLAP] — Lane A is running, OR the privacy quiet gap since the last
 *   network activity has not yet elapsed. Engine untouched; schedule re-arm after the buffer.
 * - [LaneBAction.BROADCAST] — all sources are quiet and the gap has elapsed; proceed to the SDK.
 */
internal enum class LaneBAction { BROADCAST, DEFER_OVERLAP, SHIFT, NOTHING }

/**
 * Pure preflight decision for Lane B.
 *
 * Takes pre-computed scalars so it is unit-testable without Koin, WorkManager or a real SDK.
 *
 * [lastNetworkActivityEpochSeconds] is null when no broadcast has ever been stamped (first run);
 * in that case the gap check is skipped and BROADCAST is returned.
 */
internal fun decideLaneBPreflight(
    laneARunning: Boolean,
    synchronizerSyncing: Boolean,
    nowEpochSeconds: Long,
    lastNetworkActivityEpochSeconds: Long?,
    privacyBufferSeconds: Long,
): LaneBAction {
    if (laneARunning || synchronizerSyncing) return LaneBAction.DEFER_OVERLAP
    if (lastNetworkActivityEpochSeconds != null &&
        nowEpochSeconds - lastNetworkActivityEpochSeconds < privacyBufferSeconds
    ) {
        return LaneBAction.DEFER_OVERLAP
    }
    return LaneBAction.BROADCAST
}

/**
 * Returns true if a completed sync has been observed since the last shift for this account.
 *
 * A sync is considered "completed since last shift" when [lastActivity] is non-null (meaning a
 * network broadcast has been stamped) AND it is strictly after [lastShift] (the timestamp of the
 * most recent reschedule for this transfer). If either is null the function returns false:
 * - [lastActivity] null  → no network broadcast ever recorded → no completed sync observed
 * - [lastShift] null     → no previous shift → treat as "before all time"; if lastActivity is
 *   non-null a sync HAS completed since the beginning, so return true in that case.
 *
 * Exposed as a top-level function so it can be unit-tested in isolation (both providers return
 * [java.time.Instant] which is easy to construct without Android infrastructure).
 */
internal fun syncCompletedSince(lastActivity: Instant?, lastShift: Instant?): Boolean {
    if (lastActivity == null) return false
    // No previous shift means we treat shift time as the epoch (beginning of time) — any
    // recorded activity is "since" then.
    val shiftEpoch = lastShift ?: Instant.EPOCH
    return lastActivity > shiftEpoch
}
