package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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

    private val migrationPlanRepository: MigrationPlanRepository by inject()
    private val migrationNotifier: MigrationNotifier by inject()

    override suspend fun doWork(): Result {
        val plan = migrationPlanRepository.load()
        val next = plan?.nextPending
        if (next == null) {
            Twig.debug { "MigrationNotifyWorker: no pending transfer." }
            return Result.success()
        }
        migrationNotifier.notifyManualConfirmationRequired(next.index + 1, plan.totalCount)
        Twig.debug { "MigrationNotifyWorker: notified for transfer ${next.index + 1}/${plan.totalCount}." }
        return Result.success()
    }
}
