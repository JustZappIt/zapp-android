// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.R
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome

/** Intent extra: a notification tap that should open the conversion under way. */
const val PRIVATE_USD_REVERSE_EXTRA = "private_usd_reverse_conversion"

const val PRIVATE_USD_CONVERSION_EXTRA = "private_usd_conversion"

/** Notifications for conversions. None names an amount: they show on the lock screen. */
class AtomicSwapNotifier(
    private val context: Context,
) {
    // Made on first use, so builds without conversions never list these channels.
    private val manager by lazy {
        NotificationManagerCompat.from(context).apply {
            createNotificationChannel(
                NotificationChannelCompat
                    .Builder(PROGRESS_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.private_usd_channel_progress))
                    .setDescription(context.getString(R.string.private_usd_channel_progress_description))
                    .build(),
            )
            createNotificationChannel(
                NotificationChannelCompat
                    .Builder(UPDATES_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.private_usd_channel_updates))
                    .setDescription(context.getString(R.string.private_usd_channel_updates_description))
                    .build(),
            )
        }
    }

    fun foregroundInfo(text: String, reverse: Boolean = false): ForegroundInfo {
        manager
        val notification =
            builder(PROGRESS_CHANNEL, R.string.private_usd_banner_title, text, reverse)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    fun finished(outcome: AtomicSwapOutcome) {
        val (title, body) =
            when (outcome) {
                AtomicSwapOutcome.Paid -> {
                    R.string.private_usd_notification_paid_title to R.string.private_usd_notification_paid_body
                }

                is AtomicSwapOutcome.Refunded -> {
                    R.string.private_usd_notification_refunded_title to R.string.private_usd_notification_refunded_body
                }

                is AtomicSwapOutcome.NothingSent -> {
                    R.string.private_usd_notification_nothing_title to R.string.private_usd_notification_nothing_body
                }
            }
        manager.cancel(NEEDS_YOU_ID)
        post(RESULT_ID, builder(UPDATES_CHANNEL, title, context.getString(body)).setAutoCancel(true).build())
    }

    fun reverseFinished(
        @StringRes message: Int
    ) {
        manager.cancel(NEEDS_YOU_ID)
        post(
            RESULT_ID,
            builder(UPDATES_CHANNEL, R.string.reverse_title, context.getString(message), true)
                .setAutoCancel(true)
                .build()
        )
    }

    fun needsYou(reverse: Boolean = false) {
        val body = context.getString(R.string.private_usd_notification_needs_you_body)
        post(
            NEEDS_YOU_ID,
            builder(UPDATES_CHANNEL, R.string.private_usd_notification_needs_you_title, body, reverse)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build(),
        )
    }

    fun clearNeedsYou() = manager.cancel(NEEDS_YOU_ID)

    // areNotificationsEnabled() is the real guard; lint can't see it as a permission check.
    @SuppressLint("MissingPermission")
    private fun post(
        id: Int,
        notification: Notification
    ) {
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    private fun builder(
        channel: String,
        @StringRes title: Int,
        text: String,
        reverse: Boolean = false,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_private_usd)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openConversion(reverse))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat
                    .Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification_private_usd)
                    .setContentTitle(context.getString(R.string.private_usd_notification_public))
                    .setShowWhen(false)
                    .build(),
            )

    private fun openConversion(reverse: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE + if (reverse) 1 else 0,
            Intent(context, MainActivity::class.java).apply {
                putExtra(PRIVATE_USD_CONVERSION_EXTRA, true)
                putExtra(PRIVATE_USD_REVERSE_EXTRA, reverse)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val PROGRESS_CHANNEL = "private_usd_progress"
        const val UPDATES_CHANNEL = "private_usd_updates"
        const val PROGRESS_ID = 0x50_5553
        const val RESULT_ID = PROGRESS_ID + 1
        const val NEEDS_YOU_ID = PROGRESS_ID + 2
        const val REQUEST_CODE = 0x40_0000
    }
}
