package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.ext.ZcashSdk
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manual-delivery-mode counterpart to [MigrationWorker]. Fires the "ready to send" notification
 * for the next pending transfer but never broadcasts — the user must open the app and confirm
 * via the Progress screen's Send Now action. Scheduled instead of [MigrationWorker] whenever the
 * active migration plan's delivery mode is MANUAL (background delivery was unavailable).
 */
@Keep
class MigrationNotifyWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters), KoinComponent {

    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val migrationPlanRepository: MigrationPlanRepository by inject()
    private val migrationNotifier: MigrationNotifier by inject()

    override suspend fun doWork(): Result {
        val plan = migrationPlanRepository.load()
        val next = plan?.nextPending
        if (next == null) {
            Twig.debug { "MigrationNotifyWorker: no pending transfer." }
            return Result.success()
        }
        val sdk = getOrchardMigrationSdk() ?: run {
            Twig.debug { "MigrationNotifyWorker: no wallet available — skipping." }
            return Result.success()
        }
        // A transfer awaiting a deferred proof (sign-now/prove-later — its funding note isn't
        // witnessed yet) can already read as MigrationPlan-PENDING/due before the engine actually
        // considers it broadcastable — notifying "ready to send" here would be premature: the
        // user would tap Send Now and nothing would happen. finalizeReadyTransfers() completes it
        // if its note has since become witnessed; hasOverdueTransfers() is the engine's own
        // readiness+due check (gated on the transfer actually being broadcastable), not a plain
        // wall-clock guess, so it's the correct predicate for "should I notify now."
        sdk.finalizeReadyTransfers()
        if (!sdk.hasOverdueTransfers()) {
            val delay = ZcashSdk.BLOCK_INTERVAL_MILLIS.milliseconds
            MigrationScheduler(applicationContext).scheduleNotifyOnly(delay)
            Twig.debug { "MigrationNotifyWorker: transfer ${next.index + 1} not ready yet — rechecking in $delay." }
            return Result.success()
        }
        migrationNotifier.notifyManualConfirmationRequired(next.index + 1, plan.totalCount)
        Twig.debug { "MigrationNotifyWorker: notified for transfer ${next.index + 1}/${plan.totalCount}." }
        return Result.success()
    }
}
