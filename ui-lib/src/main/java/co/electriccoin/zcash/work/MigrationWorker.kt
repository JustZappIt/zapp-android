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
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
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
    private val migrationPlanRepository: MigrationPlanRepository by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider by inject()
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider by inject()

    override suspend fun doWork(): Result {
        val sdk = getOrchardMigrationSdk() ?: run {
            Twig.debug { "MIGRATION_DIAG MigrationWorker: no wallet available — skipping." }
            return Result.success()
        }

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
        return when (val result = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor))) {
            null -> {
                if (next != null) {
                    // A transfer is still pending but wasn't ready to broadcast this run — most
                    // commonly its funding note isn't witnessed at the freshly-computed anchor
                    // yet (design spec §6's ordinary transient state, not a failure). Unlike the
                    // TransferResult.Success branch below, nothing else re-arms a future attempt
                    // for this case, so without rescheduling here the plan would silently stall
                    // until the user happens to notice an overdue transfer and manually
                    // reschedules from Migration Progress. One block interval is the finest
                    // granularity at which the underlying chain state can actually change.
                    val delay = ZcashSdk.BLOCK_INTERVAL_MILLIS.milliseconds
                    MigrationScheduler(applicationContext).schedule(delay)
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: no pending transfer yet — retrying in $delay." }
                } else {
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: no pending transfer." }
                }
                Result.success()
            }
            is TransferResult.Success -> {
                Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer sent — txId=${result.txId}" }
                val updatedPlan = migrationPlanRepository.load()
                if (updatedPlan?.nextPending != null) {
                    val delay = nextDelay(updatedPlan)
                    MigrationScheduler(applicationContext).schedule(delay)
                    migrationNotifier.notifyTransferComplete(updatedPlan.completedCount, updatedPlan.totalCount)
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: next transfer scheduled in $delay" }
                } else {
                    migrationNotifier.notifyMigrationComplete()
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: migration complete!" }
                }
                Result.success()
            }
            is TransferResult.NetworkError -> {
                Twig.debug { "MIGRATION_DIAG MigrationWorker: network error, retryable=${result.retryable}" }
                if (result.retryable) {
                    Result.retry()
                } else if (useTor) {
                    // A non-retryable network error while Tor was in use for this attempt is
                    // presumptively a Tor-connectivity failure, same reasoning as
                    // MigrationSendingVM.sendOnce()'s interactive NetworkError branch. Persist a
                    // flag so app-open reconciliation (CheckMigrationRecoveryUseCase) routes back
                    // through the Sending screen instead of the generic manual-confirmation path,
                    // and surface a distinct notification so this looks different from any other
                    // missed transfer.
                    pendingMigrationTorFailureStorageProvider.store(true)
                    migrationNotifier.notifyMigrationTorFailure()
                    Result.failure()
                } else {
                    // Nothing else re-arms a future attempt for a non-retryable failure — the
                    // user must open the app and act, same as a missed/stalled window.
                    if (next != null) migrationNotifier.notifyManualConfirmationRequired(next.index + 1, plan.totalCount)
                    Result.failure()
                }
            }
            TransferResult.InvalidNote -> {
                // State is now RequiresAttention(InvalidTransfer) — spec §6.2, notes were spent
                // outside the migration flow. On-launch reconciliation will surface the prompt, but
                // the user still needs telling since nothing else runs meanwhile.
                Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer invalid (note spent externally) — user action required on next open." }
                migrationNotifier.notifyMigrationPlanInvalid()
                Result.success()
            }
            TransferResult.Expired -> {
                // State is now RequiresAttention(TransferExpired) — spec §6.3, the transfer's
                // anchor expired before it could broadcast (the app wasn't opened in time). Distinct
                // user-facing copy from InvalidNote above, even though both branches otherwise
                // handle identically (no further action possible from the background worker).
                Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer expired — user action required on next open." }
                migrationNotifier.notifyTransferExpired()
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
