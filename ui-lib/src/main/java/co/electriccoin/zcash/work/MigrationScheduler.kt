package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * No iOS-style margin/cadence-reset constants (first +30 min, then +6.5 h) are ported here on
 * purpose: unlike iOS's BGProcessingTask, [androidx.work.WorkManager.enqueueUniqueWork] with
 * [androidx.work.OneTimeWorkRequest.Builder.setInitialDelay] is driven directly and
 * deterministically from the SDK's authoritative `scheduledAt`/height-derived time — there's no
 * OS-level "best-effort earliest begin" uncertainty to buffer against. The real Android-side
 * timing risk is Doze/App-Standby deferral, and that's already handled by the overdue-recovery
 * self-healing path (`CheckMigrationRecoveryUseCase` + `hasOverdueTransfers()`), not by adding
 * scheduling margins here — except for the specific case where the app can't run in the
 * background at all (see [MigrationDueAlarmScheduler]), where an inexact-while-idle `AlarmManager`
 * alarm is armed/cancelled alongside the WorkManager job below purely to surface a "ready to send"
 * notification (spec §6.4), never to run the transfer itself.
 */
class MigrationScheduler(private val context: Context) {
    // Armed/cancelled alongside the WorkManager job below rather than threaded through every call
    // site separately — see MigrationDueAlarmScheduler's kdoc for why this needs its own
    // AlarmManager alarm instead of relying on the WorkManager job itself.
    private val migrationDueAlarmScheduler = MigrationDueAlarmScheduler(context)

    fun schedule(delay: Duration) {
        Twig.debug { "MIGRATION_DIAG MigrationScheduler: scheduling next migration transfer in $delay" }
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_ID,
            ExistingWorkPolicy.REPLACE,
            newWorkRequest(delay)
        )
        migrationDueAlarmScheduler.schedule(delay)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_ID)
        migrationDueAlarmScheduler.cancel()
    }

    companion object {
        const val WORK_ID = "co.electriccoin.zcash.migration_transfer"

        fun newWorkRequest(delay: Duration): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MigrationWorker>()
                .setConstraints(workConstraints())
                .setInitialDelay(delay.toJavaDuration())
                .build()

        private fun workConstraints(): Constraints =
            if (BuildConfig.DEBUG) {
                Constraints.NONE
            } else {
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            }
    }
}
