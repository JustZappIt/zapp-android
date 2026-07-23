package co.electriccoin.zcash.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import co.electriccoin.zcash.spackle.Twig
import kotlin.time.Duration

/**
 * Arms an inexact-while-idle [AlarmManager] alarm alongside [MigrationScheduler]'s WorkManager job,
 * so [MigrationTransferDueReceiver] can surface a "transfer ready to send" notification (spec §6.4)
 * even when the app can't execute in the background at all. WorkManager jobs can be deferred
 * indefinitely by Doze/App-Standby when background execution is unavailable — this alarm is still
 * allowed to fire (with reduced precision) even while the device is idle, which is exactly the gap
 * this closes.
 *
 * Deliberately uses [AlarmManager.setAndAllowWhileIdle] rather than
 * `setExactAndAllowWhileIdle`/`setAlarmClock` — a transfer's due time is typically hours away, so
 * to-the-second precision isn't needed, and exact alarms require `SCHEDULE_EXACT_ALARM`/
 * `USE_EXACT_ALARM` plus a runtime permission check on API 31+ that this feature has no reason to
 * take on. No special permission is required for the inexact variant used here.
 *
 * Owned by [MigrationScheduler] (called from its `schedule()`/`cancel()`) rather than threaded
 * through every WorkManager call site separately — every place that currently arms or cancels the
 * background worker already goes through that one class.
 */
class MigrationDueAlarmScheduler(private val context: Context) {
    fun schedule(delay: Duration) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Twig.warn { "MIGRATION_DIAG MigrationDueAlarmScheduler: AlarmManager unavailable — skipping." }
            return
        }
        val triggerAtMillis = System.currentTimeMillis() + delay.inWholeMilliseconds
        Twig.debug { "MIGRATION_DIAG MigrationDueAlarmScheduler: arming ready-to-send alarm in $delay" }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent())
    }

    fun cancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, MigrationTransferDueReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val REQUEST_CODE = 9101
    }
}
