package co.electriccoin.zcash.ui.common.provider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.accountIdOffset

class MigrationNotifier(private val context: Context) {

    private fun mainActivityIntent(accountKeyId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_MIGRATION, true)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_MIGRATION_BASE + accountIdOffset(accountKeyId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Distinct request code AND a distinct intent extra from mainActivityIntent()'s
    // EXTRA_OPEN_MIGRATION — MainActivity.handleMigrationIntent() hard-routes that existing extra
    // to MigrationProgressArgs (the missed-transfer screen), but this notification needs to land on
    // MigrationTransferReviewArgs instead (spec §6.4 is deliberately a distinct, lighter-weight
    // path from the overdue/missed-transfer recovery flow).
    private fun transferReadyToSendIntent(accountKeyId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_TRANSFER_READY, true)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_TRANSFER_READY_BASE + accountIdOffset(accountKeyId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun progressNotificationId(accountKeyId: String): Int =
        NOTIFICATION_ID_PROGRESS_BASE + accountIdOffset(accountKeyId)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ironwood Migration",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for Orchard to Ironwood migration progress"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun notifyTransferComplete(accountKeyId: String, completed: Int, total: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle("Ironwood Migration")
            .setContentText("Transfer $completed of $total complete")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainActivityIntent(accountKeyId))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    fun notifyManualConfirmationRequired(accountKeyId: String, transferIndex: Int, total: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle("Migration: Action Required")
            .setContentText("Transfer $transferIndex of $total is ready. Tap to confirm.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityIntent(accountKeyId))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    fun notifyMigrationTorFailure(accountKeyId: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle("Migration: Couldn't Connect to Tor")
            .setContentText("A scheduled transfer couldn't send over Tor. Open Zodl to resolve.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityIntent(accountKeyId))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    /**
     * Spec §6.4 "Transfer Ready to Send": posted the moment a scheduled transfer becomes due while
     * background execution is unavailable (see [co.electriccoin.zcash.ui.common.provider
     * .IsBackgroundExecutionAvailableProvider] and [co.electriccoin.zcash.work
     * .MigrationTransferDueReceiver]) — distinct from [notifyManualConfirmationRequired], which is
     * for a background broadcast that was actually attempted and failed. Tapping this routes to the
     * lighter-weight review-and-send screen ([EXTRA_OPEN_TRANSFER_READY]), not the fuller
     * Reschedule/Send-now recovery screen.
     */
    fun notifyTransferReadyToSend(accountKeyId: String, transferIndex: Int, total: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle("Ironwood Migration")
            .setContentText("Transfer $transferIndex of $total is ready to send. Tap to review and send.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(transferReadyToSendIntent(accountKeyId))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    // Spec §6.2 (Migration Plan Update) — notes were spent outside the migration flow, invalidating
    // the plan. Kept distinct from notifyTransferExpired() below (spec §6.3) even though both
    // currently deliver through the same TransferResult.InvalidNote/Expired branch in
    // MigrationWorker — the two causes read differently to the user, matching the distinct
    // Transfer Invalid screen copy (see MigrationAttentionKind).
    fun notifyMigrationPlanInvalid(accountKeyId: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle("Ironwood Migration")
            .setContentText("Migration plan needs update. Open Zodl to review the details.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityIntent(accountKeyId))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    // Spec §6.3 (Transfer(s) Expired) — one or more transfers expired without executing (the app
    // wasn't opened in time to broadcast them before their anchor expired).
    fun notifyTransferExpired(accountKeyId: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle("Ironwood Migration")
            .setContentText("A transfer expired. Open Zodl to continue your migration.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityIntent(accountKeyId))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    fun notifyMigrationComplete(accountKeyId: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle("Ironwood Migration Complete")
            .setContentText("All your funds have been migrated to Ironwood.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainActivityIntent(accountKeyId))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    companion object {
        const val CHANNEL_ID = "migration_channel"
        const val EXTRA_OPEN_MIGRATION = "co.electriccoin.zcash.migration.open_progress"
        const val EXTRA_OPEN_TRANSFER_READY = "co.electriccoin.zcash.migration.open_transfer_ready"
        // Notification-id namespace (NotificationManager ids). Independent of the PendingIntent
        // request-code namespace below — sharing the same numeric base value across the two namespaces
        // does not collide. Per-account via `+ accountIdOffset(...)` (range 0..0xFFFF).
        private const val NOTIFICATION_ID_PROGRESS_BASE = 0x10_0000
        // PendingIntent request-code namespace. The two bases are spaced 0x10_0000 apart — far more than
        // accountIdOffset's 0..0xFFFF range — so per-account request-code ranges can never overlap.
        private const val REQUEST_CODE_MIGRATION_BASE = 0x10_0000
        private const val REQUEST_CODE_TRANSFER_READY_BASE = 0x20_0000
    }
}
