package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Keep
class MigrationWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters), KoinComponent {

    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val migrationPlanRepository: MigrationPlanRepository by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider by inject()

    override suspend fun doWork(): Result {
        val sdk = getOrchardMigrationSdk() ?: run {
            Twig.debug { "MigrationWorker: no wallet available — skipping." }
            return Result.success()
        }
        // Completes any transfer that was pre-signed with a placeholder witness (sign-now/
        // prove-later pipeline) whose funding note has since become witnessed, so it can be
        // picked up by executeNextPendingTransfer() below in this same run. Cheap and safe to call
        // on every run — a no-op when nothing is awaiting a proof yet.
        val finalizedCount = sdk.finalizeReadyTransfers()
        if (finalizedCount > 0) {
            Twig.debug { "MigrationWorker: finalized $finalizedCount transfer(s) awaiting proof." }
        }

        if (sdk.isSyncRequiredBeforeNextTransfer()) {
            // Sync and broadcast must be decoupled — skip this window, reconcile on next launch.
            Twig.debug { "MigrationWorker: sync required before next transfer — skipping." }
            return Result.success()
        }

        val plan = migrationPlanRepository.load()
        val next = plan?.nextPending
        val useTor = isTorEnabledStorageProvider.get() == true
        return when (val result = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor))) {
            null -> {
                Twig.debug { "MigrationWorker: no pending transfer." }
                Result.success()
            }
            is TransferResult.Success -> {
                Twig.debug { "MigrationWorker: transfer sent — txId=${result.txId}" }
                val updatedPlan = migrationPlanRepository.load()
                if (updatedPlan?.nextPending != null) {
                    val delay = nextDelay(updatedPlan)
                    MigrationScheduler(applicationContext).schedule(delay)
                    migrationNotifier.notifyTransferComplete(updatedPlan.completedCount, updatedPlan.totalCount)
                    Twig.debug { "MigrationWorker: next transfer scheduled in $delay" }
                } else {
                    migrationNotifier.notifyMigrationComplete()
                    Twig.debug { "MigrationWorker: migration complete!" }
                }
                Result.success()
            }
            is TransferResult.NetworkError -> {
                Twig.debug { "MigrationWorker: network error, retryable=${result.retryable}" }
                if (result.retryable) {
                    Result.retry()
                } else {
                    // Nothing else re-arms a future attempt for a non-retryable failure — the
                    // user must open the app and act, same as a missed/stalled window.
                    if (next != null) migrationNotifier.notifyManualConfirmationRequired(next.index + 1, plan.totalCount)
                    Result.failure()
                }
            }
            TransferResult.InvalidNote,
            TransferResult.Expired -> {
                // State is now RequiresAttention — on-launch reconciliation will surface the
                // prompt, but the user still needs telling since nothing else runs meanwhile.
                Twig.debug { "MigrationWorker: transfer invalid or expired — user action required on next open." }
                migrationNotifier.notifyMigrationPlanInvalid()
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
