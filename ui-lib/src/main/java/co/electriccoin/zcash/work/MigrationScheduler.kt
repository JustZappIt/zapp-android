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
 * scheduling margins here.
 */
class MigrationScheduler(private val context: Context) {
    // The two queues are mutually exclusive by construction — a plan is either SCHEDULED or
    // MANUAL, never both — so scheduling one always cancels the other first. Without this, a
    // plan that switches delivery mode (recreated after recovery, etc.) could leave a stale job
    // armed under the previous mode's unique work name, since REPLACE only clobbers same-named
    // work.
    fun schedule(delay: Duration) {
        Twig.debug { "MigrationScheduler: scheduling next migration transfer in $delay" }
        cancelNotifyOnly()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_ID,
            ExistingWorkPolicy.REPLACE,
            newWorkRequest(delay)
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_ID)
    }

    // Manual-delivery-mode counterpart to schedule() — fires a "ready to send" notification for
    // the next pending transfer instead of broadcasting it. Uses a distinct unique work name so
    // it can never collide with (REPLACE-clobber, or be clobbered by) a real send job.
    fun scheduleNotifyOnly(delay: Duration) {
        Twig.debug { "MigrationScheduler: scheduling notify-only check in $delay" }
        cancel()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOTIFY_WORK_ID,
            ExistingWorkPolicy.REPLACE,
            newNotifyWorkRequest(delay)
        )
    }

    fun cancelNotifyOnly() {
        WorkManager.getInstance(context).cancelUniqueWork(NOTIFY_WORK_ID)
    }

    companion object {
        const val WORK_ID = "co.electriccoin.zcash.migration_transfer"
        const val NOTIFY_WORK_ID = "$WORK_ID.notify"

        fun newWorkRequest(delay: Duration): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MigrationWorker>()
                .setConstraints(workConstraints())
                .setInitialDelay(delay.toJavaDuration())
                .build()

        fun newNotifyWorkRequest(delay: Duration): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MigrationNotifyWorker>()
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
