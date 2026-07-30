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

class MigrationNotifier(
    private val context: Context
) {
    private fun mainActivityIntent(accountKeyId: String): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_MIGRATION, true)
                putExtra(EXTRA_ACCOUNT_KEY_ID, accountKeyId)
            }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_MIGRATION_BASE + accountIdOffset(accountKeyId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Distinct request code AND a distinct intent extra from mainActivityIntent()'s
    // EXTRA_OPEN_MIGRATION: the step-due tap must RE-KICK the worker (handleIntent schedules an
    // immediate run) besides opening Progress — background execution needs no UI, the app open
    // exists only to give the OS a live process to run the worker in.
    private fun runStepIntent(accountKeyId: String): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_RUN_STEP, true)
                putExtra(EXTRA_ACCOUNT_KEY_ID, accountKeyId)
            }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_TRANSFER_READY_BASE + accountIdOffset(accountKeyId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stepDueNotificationId(accountKeyId: String): Int =
        NOTIFICATION_ID_STEP_DUE_BASE + accountIdOffset(accountKeyId)

    private fun progressNotificationId(accountKeyId: String): Int =
        NOTIFICATION_ID_PROGRESS_BASE + accountIdOffset(accountKeyId)

    fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Ironwood Migration",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for Orchard to Ironwood migration progress"
            }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Progress of the note-split (preparation) phase — splits are internal plumbing, so they never
     * announce crossing counts ("Transfer 0 of 11 complete" read as zero progress); they announce
     * their own.
     */
    fun notifyNoteSplitProgress(accountKeyId: String, completedSplits: Int, totalSplits: Int) {
        val contentText =
            if (totalSplits > 0) {
                "Note split $completedSplits of $totalSplits"
            } else {
                "Preparing your balance for migration"
            }
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert_circle)
                .setContentTitle("Ironwood Migration")
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainActivityIntent(accountKeyId))
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    fun notifyTransferComplete(accountKeyId: String, completed: Int, total: Int) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert_circle)
                .setContentTitle("Ironwood Migration")
                .setContentText("Transfer $completed of $total complete")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainActivityIntent(accountKeyId))
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    /**
     * Strict-order escalation: the plan's head transfer stayed unprovable across a completed sync
     * — with strict ordering everything behind it is blocked, so the user must reschedule (the
     * app-open recovery routes to the invalid/reschedule screen).
     */
    fun notifyRescheduleRequired(accountKeyId: String, transferIndex: Int, total: Int) {
        val contentText =
            if (total > 0 && transferIndex > 0) {
                "Transfer $transferIndex of $total can't be sent — tap to reschedule the migration."
            } else {
                "The migration is blocked — tap to reschedule."
            }
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert_circle)
                .setContentTitle("Ironwood Migration")
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(mainActivityIntent(accountKeyId))
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    fun notifyManualConfirmationRequired(accountKeyId: String, transferIndex: Int, total: Int) {
        // F7: render real "Transfer X of Y" counts when the caller has them; fall back to generic
        // copy when they're unknown (total <= 0 or index <= 0) instead of the meaningless
        // "Transfer 0 of 0" the escalation call site used to pass.
        val contentText =
            if (total > 0 && transferIndex > 0) {
                "Transfer $transferIndex of $total is ready. Tap to confirm."
            } else {
                "A migration transfer is ready. Tap to confirm."
            }
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert_circle)
                .setContentTitle("Migration: Action Required")
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(mainActivityIntent(accountKeyId))
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    fun notifyMigrationTorFailure(accountKeyId: String) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
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
     * Dead-man's-switch fallback (design 2026-07-30): the worker missed its expected run — a
     * migration STEP (prove or broadcast; everything is pre-signed, no user review exists) is due
     * and nothing is executing it. Tapping opens the app, which silently re-kicks the worker; the
     * worker's own next run start cancels this via [cancelStepDue].
     */
    fun notifyMigrationStepDue(accountKeyId: String) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert_circle)
                .setContentTitle("Ironwood Migration")
                .setContentText("Your migration is ready to continue — tap to run the next step.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(runStepIntent(accountKeyId))
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat.from(context).notify(stepDueNotificationId(accountKeyId), notification)
    }

    /** The worker ran — the step-due fallback (if showing) is obsolete. */
    fun cancelStepDue(accountKeyId: String) {
        NotificationManagerCompat.from(context).cancel(stepDueNotificationId(accountKeyId))
    }

    // Spec §6.2 (Migration Plan Update) — notes were spent outside the migration flow, invalidating
    // the plan. Kept distinct from notifyTransferExpired() below (spec §6.3) even though both
    // currently deliver through the same TransferResult.InvalidNote/Expired branch in
    // MigrationWorker — the two causes read differently to the user, matching the distinct
    // Transfer Invalid screen copy (see MigrationAttentionKind).
    fun notifyMigrationPlanInvalid(accountKeyId: String) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
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
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert_circle)
                .setContentTitle("Ironwood Migration")
                .setContentText("A transfer expired. Open Zodl to continue your migration.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(mainActivityIntent(accountKeyId))
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat.from(context).notify(progressNotificationId(accountKeyId), notification)
    }

    /**
     * Dismisses whatever migration notification is currently showing for [accountKeyId]. All
     * notify* methods above share the single per-account [progressNotificationId], so one cancel
     * covers them all — used when the migration itself is discarded (debug "Migration restart"),
     * where a leftover "ready to send"/"Tor failure" notification would tap into a migration that
     * no longer exists.
     */
    fun cancel(accountKeyId: String) {
        NotificationManagerCompat.from(context).cancel(progressNotificationId(accountKeyId))
    }

    fun notifyMigrationComplete(accountKeyId: String) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
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
        const val EXTRA_RUN_STEP = "co.electriccoin.zcash.migration.run_step"

        /**
         * The storage-key id ([co.electriccoin.zcash.ui.common.model.toStorageKeyId]) of the
         * account this notification belongs to. `handleIntent` selects that account before
         * navigating, so tapping a Keystone account's migration notification while the Zodl
         * account is selected lands on the RIGHT account's migration screens.
         */
        const val EXTRA_ACCOUNT_KEY_ID = "co.electriccoin.zcash.migration.account_key_id"

        // Notification-id namespace (NotificationManager ids). Independent of the PendingIntent
        // request-code namespace below — sharing the same numeric base value across the two namespaces
        // does not collide. Per-account via `+ accountIdOffset(...)` (range 0..0xFFFF).
        private const val NOTIFICATION_ID_PROGRESS_BASE = 0x10_0000
        private const val NOTIFICATION_ID_STEP_DUE_BASE = 0x40_0000

        // PendingIntent request-code namespace. The two bases are spaced 0x10_0000 apart — far more than
        // accountIdOffset's 0..0xFFFF range — so per-account request-code ranges can never overlap.
        private const val REQUEST_CODE_MIGRATION_BASE = 0x10_0000
        private const val REQUEST_CODE_TRANSFER_READY_BASE = 0x20_0000
    }
}
