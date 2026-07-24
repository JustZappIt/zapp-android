package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.ext.ZcashSdk
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    override suspend fun doWork(): Result {
        val sdk = getOrchardMigrationSdk() ?: run {
            Twig.debug { "MIGRATION_DIAG MigrationWorker: no wallet available — skipping." }
            return Result.success()
        }
        val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()

        // Completes any transfer that was pre-signed with a placeholder witness (sign-now/
        // prove-later pipeline) whose funding note has since become witnessed, so it can be
        // picked up by executeNextPendingTransfer() below in this same run. Cheap and safe to call
        // on every run — a no-op when nothing is awaiting a proof yet.
        val finalizedCount = sdk.finalizeReadyTransfers()
        if (finalizedCount > 0) {
            Twig.debug { "MIGRATION_DIAG MigrationWorker: finalized $finalizedCount transfer(s) awaiting proof." }
        }

        val plan = migrationPlanRepository.load()
        val next = plan?.nextPending
        val useTor = isMigrationTorEnabledStorageProvider.get()
        // Retries within this single worker invocation, same attempt count (3) as
        // MigrationSendingVM.sendOnce()'s foreground loop — but a different trigger: sendOnce()
        // retries while the result is null (still polling for readiness) and stops on any
        // non-null result, while this retries only on a retryable NetworkError and stops
        // immediately on null. So a persistent network error settles into an error state after 3
        // attempts instead of retrying via WorkManager's Result.retry() indefinitely (previously
        // observed: dumpsys jobscheduler showed the same worker restarting and running for the
        // full ~10-minute execution ceiling, repeatedly, for hours).
        return when (val result = executeWithRetries { sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor)) }) {
            null -> {
                when (
                    decideNullResultAction(
                        hasNextPending = next != null,
                        isOverdue = next != null && sdk.hasOverdueTransfers(),
                    )
                ) {
                    NullResultAction.HANDOFF_TO_APP -> {
                        // sdk.hasOverdueTransfers() is height-based — a confirmed fact, not this
                        // transfer's own wall-clock scheduledAt estimate — so we KNOW the wallet
                        // can't make progress here on its own. Background execution never drives
                        // sync itself (sync/broadcast timing must stay decoupled — see
                        // isSyncBlocked()'s KDoc), so nothing else will ever unstick this purely
                        // from the background. Stop silently rescheduling and hand off to the
                        // Resume Migration screen (Send Now / Reschedule), same as the
                        // NetworkError/InvalidNote branches below already do.
                        Twig.debug {
                            "MIGRATION_DIAG MigrationWorker: transfer overdue and still not ready — handing off to Resume Migration."
                        }
                        if (next != null) migrationNotifier.notifyManualConfirmationRequired(accountKeyId, next.index + 1, plan.totalCount)
                        Result.failure()
                    }
                    NullResultAction.WAIT_AND_RETRY -> {
                        // A transfer is still pending but wasn't ready to broadcast this run — most
                        // commonly its funding note isn't witnessed at the freshly-computed anchor
                        // yet (design spec §6's ordinary transient state, not a failure). One block
                        // interval is the finest granularity at which the underlying chain state can
                        // actually change.
                        val delay = ZcashSdk.BLOCK_INTERVAL_MILLIS.milliseconds
                        MigrationScheduler(applicationContext).schedule(accountKeyId, delay)
                        Twig.debug { "MIGRATION_DIAG MigrationWorker: no pending transfer yet — retrying in $delay." }
                        Result.success()
                    }
                    NullResultAction.NOTHING_PENDING -> {
                        Twig.debug { "MIGRATION_DIAG MigrationWorker: no pending transfer." }
                        Result.success()
                    }
                }
            }
            is TransferResult.Success -> {
                Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer sent — txId=${result.txId}" }
                val updatedPlan = migrationPlanRepository.load()
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
                    pendingMigrationTorFailureStorageProvider.store(true)
                    migrationNotifier.notifyMigrationTorFailure(accountKeyId)
                } else if (next != null) {
                    // Nothing else re-arms a future attempt for a non-retryable failure — the
                    // user must open the app and act, same as a missed/stalled window.
                    migrationNotifier.notifyManualConfirmationRequired(accountKeyId, next.index + 1, plan.totalCount)
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
    }

    private fun nextDelay(plan: MigrationPlan): Duration {
        val next = plan.nextPending ?: return 0.seconds
        val remaining = next.scheduledAt - Clock.System.now()
        return if (remaining.isNegative()) 0.seconds else remaining
    }
}

// Same attempt count (3) as MigrationSendingVM.sendOnce()'s foreground retry loop — but not the
// same retry trigger: sendOnce() retries while polling for readiness (result == null) and stops
// on any non-null result; this retries only on a retryable NetworkError and stops on null. Each
// loop is correct for its own context (foreground polls for the transfer becoming ready;
// background rides out a flaky network) — they just happen to share the same attempt budget.
private const val MAX_BROADCAST_ATTEMPTS = 3
private const val BROADCAST_RETRY_DELAY_MS = 1500L

/**
 * Calls [attempt] up to [maxAttempts] times, retrying only while the result is a retryable
 * [TransferResult.NetworkError] — anything else (success, not-yet-due null, a non-retryable
 * error, an invalid/expired transfer) short-circuits immediately. Top-level and `internal`
 * (rather than a private method on [MigrationWorker]) specifically so it's unit-testable without
 * Koin or WorkManager, neither of which this codebase has test infrastructure for today.
 */
internal suspend fun executeWithRetries(
    maxAttempts: Int = MAX_BROADCAST_ATTEMPTS,
    retryDelayMs: Long = BROADCAST_RETRY_DELAY_MS,
    attempt: suspend () -> TransferResult?,
): TransferResult? {
    var result: TransferResult? = null
    for (i in 0 until maxAttempts) {
        if (i > 0) delay(retryDelayMs)
        result = attempt()
        val current = result
        if (current !is TransferResult.NetworkError || !current.retryable) break
    }
    return result
}

/**
 * What MigrationWorker's `null` (nothing due/ready) branch should do next. Takes pre-computed
 * booleans (rather than the SDK/plan directly) specifically so it's unit-testable without Koin,
 * WorkManager, or a real OrchardMigrationSdk.
 */
internal enum class NullResultAction { WAIT_AND_RETRY, HANDOFF_TO_APP, NOTHING_PENDING }

internal fun decideNullResultAction(hasNextPending: Boolean, isOverdue: Boolean): NullResultAction =
    when {
        !hasNextPending -> NullResultAction.NOTHING_PENDING
        isOverdue -> NullResultAction.HANDOFF_TO_APP
        else -> NullResultAction.WAIT_AND_RETRY
    }
