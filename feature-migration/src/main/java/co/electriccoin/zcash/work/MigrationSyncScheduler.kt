package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import co.electriccoin.zcash.migration.BuildConfig
import co.electriccoin.zcash.spackle.Twig
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Schedules the Lane A migration sync worker ([MigrationSyncWorker]) as a wallet-global unique
 * work item (not per-account-suffixed — there is one sync lane per wallet). Uses
 * [ExistingWorkPolicy.REPLACE] so re-arming always supplants the previous pending job.
 *
 * Unlike [MigrationScheduler], this scheduler does NOT manage any AlarmManager alarm — Lane A is
 * a periodic sync heartbeat, not a user-visible "ready to send" signal, so no due-alarm is needed.
 */
class MigrationSyncScheduler(
    private val context: Context
) {
    fun schedule(accountKeyId: String, delay: Duration) {
        Twig.debug { "MIGRATION_DIAG MigrationSyncScheduler: scheduling next Lane A sync for $accountKeyId in $delay" }
        WorkManager.getInstance(context).enqueueUniqueWork(
            WorkIds.WORK_ID_MIGRATION_SYNC,
            ExistingWorkPolicy.REPLACE,
            newWorkRequest(accountKeyId, delay),
        )
    }

    fun cancel(accountKeyId: String) {
        Twig.debug { "MIGRATION_DIAG MigrationSyncScheduler: cancelling Lane A sync for $accountKeyId" }
        WorkManager.getInstance(context).cancelUniqueWork(WorkIds.WORK_ID_MIGRATION_SYNC)
    }

    companion object {
        fun newWorkRequest(accountKeyId: String, delay: Duration): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MigrationSyncWorker>()
                .setConstraints(workConstraints())
                .setInitialDelay(delay.toJavaDuration())
                .setInputData(workDataOf(MigrationScheduler.KEY_ACCOUNT_KEY_ID to accountKeyId))
                .build()

        private fun workConstraints(): Constraints =
            if (BuildConfig.DEBUG) {
                Constraints.NONE
            } else {
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            }
    }
}
