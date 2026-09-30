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
import xyz.justzappit.offramp.atomicswap.SwapDirection

/** Intent extra: the [SwapDirection] of the conversion a notification tap opens. */
const val PRIVATE_USD_CONVERSION_EXTRA = "private_usd_conversion_direction"

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

    fun foregroundInfo(
        text: String,
        direction: SwapDirection
    ): ForegroundInfo {
        manager
        val notification =
            builder(PROGRESS_CHANNEL, R.string.private_usd_banner_title, text, direction)
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

    fun finished(
        direction: SwapDirection,
        @StringRes title: Int,
        @StringRes body: Int,
    ) {
        manager.cancel(NEEDS_YOU_ID)
        post(RESULT_ID, builder(UPDATES_CHANNEL, title, context.getString(body), direction).setAutoCancel(true).build())
    }

    fun needsYou(direction: SwapDirection) {
        val body =
            when (direction) {
                SwapDirection.FORWARD -> R.string.private_usd_notification_needs_you_body
                SwapDirection.REVERSE -> R.string.reverse_notification_needs_you_body
            }
        val text = context.getString(body)
        post(
            NEEDS_YOU_ID,
            builder(UPDATES_CHANNEL, R.string.private_usd_notification_needs_you_title, text, direction)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build(),
        )
    }

    fun clearNeedsYou() = manager.cancel(NEEDS_YOU_ID)

    /** Takes back every conversion notification still showing. */
    fun clear() {
        manager.cancel(RESULT_ID)
        manager.cancel(NEEDS_YOU_ID)
    }

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
        direction: SwapDirection,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_private_usd)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openConversion(direction))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat
                    .Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification_private_usd)
                    .setContentTitle(context.getString(R.string.private_usd_notification_public))
                    .setShowWhen(false)
                    .build(),
            )

    private fun openConversion(direction: SwapDirection): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE + direction.ordinal,
            Intent(context, MainActivity::class.java).apply {
                putExtra(PRIVATE_USD_CONVERSION_EXTRA, direction.name)
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
