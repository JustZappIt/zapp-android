// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Keeps the swap under way advancing outside the app's screens: the worker holds the process up while
 * it runs, and two alarms wake the app around `t0` and before `t1` in case Android stopped it.
 */
class AtomicSwapScheduler(
    private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Starts the worker afresh; from the foreground, so it can run as a foreground service. */
    fun runNow() = workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, expedited())

    fun runIfIdle() = workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, expedited())

    /** From a worker about to end: the next run follows it. */
    fun runAfter(delay: Duration) {
        val request =
            OneTimeWorkRequestBuilder<AtomicSwapWorker>()
                .setConstraints(CONNECTED)
                .setInitialDelay(delay.toJavaDuration())
                .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** The app may claim without the maker from [t0]; after [t1] the maker may take its side back. */
    fun wakeAt(
        t0: Long,
        t1: Long
    ) {
        alarm(Wake.CLAIMABLE, t0 + CLAIMABLE_SLACK.inWholeSeconds)
        alarm(Wake.LAST_CALL, maxOf(t0 + LAST_CALL_AFTER_T0.inWholeSeconds, t1 - LAST_CALL_BEFORE_T1.inWholeSeconds))
    }

    fun cancelWakes() {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        Wake.entries.forEach { alarms.cancel(wakeIntent(it)) }
    }

    // Inexact but allowed while the phone idles; no exact-alarm permission needed.
    private fun alarm(
        wake: Wake,
        atSeconds: Long
    ) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atSeconds * MILLIS, wakeIntent(wake))
    }

    private fun wakeIntent(wake: Wake): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE + wake.ordinal,
            Intent(context, AtomicSwapWakeReceiver::class.java).putExtra(EXTRA_WAKE, wake.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    enum class Wake { CLAIMABLE, LAST_CALL }

    companion object {
        const val EXTRA_WAKE = "atomicswap_wake"
        private const val WORK_NAME = "co.electriccoin.zcash.atomicswap_advance"
        private const val REQUEST_CODE = 0x50_0000
        private const val MILLIS = 1000L
        private val CLAIMABLE_SLACK = 30.seconds
        private val LAST_CALL_AFTER_T0 = 3.minutes
        private val LAST_CALL_BEFORE_T1 = 5.minutes
        private val CONNECTED = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun expedited(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AtomicSwapWorker>()
                .setConstraints(CONNECTED)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
    }
}
