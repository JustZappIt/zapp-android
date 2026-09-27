// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the process up while a swap is under way, as a foreground service when Android allows one.
 * The repository does the advancing; a run that has to end early hands over to the next.
 */
@Keep
class AtomicSwapWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters),
    KoinComponent {
    private val repository: AtomicSwapRepository by inject()
    private val notifier: AtomicSwapNotifier by inject()

    override suspend fun doWork(): Result {
        if (!repository.isUnderWay()) return Result.success()
        val isForeground = promote(applicationContext.getString(AtomicSwapStage.of(repository.state.value).label))
        repository.resume(isForeground = false)
        val settled =
            coroutineScope {
                val updates = if (isForeground) launch { keepNotificationCurrent() } else null
                withTimeoutOrNull(if (isForeground) FOREGROUND_BUDGET else BACKGROUND_BUDGET) {
                    repository.awaitSettled()
                }.also { updates?.cancel() }
            } != null
        if (!settled) AtomicSwapScheduler(applicationContext).runAfter(FOLLOW_UP_DELAY)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.foregroundInfo(applicationContext.getString(R.string.convert_progress_title))

    private suspend fun keepNotificationCurrent() {
        repository.state
            .map { AtomicSwapStage.of(it) }
            .distinctUntilChanged()
            .collect { stage -> promote(applicationContext.getString(stage.label)) }
    }

    private suspend fun promote(text: String): Boolean =
        try {
            setForeground(notifier.foregroundInfo(text))
            true
        } catch (e: IllegalStateException) {
            Twig.info { "Atomic swap: no foreground service from here, ${e.message}" }
            false
        } catch (e: SecurityException) {
            Twig.info { "Atomic swap: no foreground service in this build, ${e.message}" }
            false
        }

    private companion object {
        val FOREGROUND_BUDGET = 3.hours

        // Android stops a worker that isn't a foreground service after ten minutes.
        val BACKGROUND_BUDGET = 9.minutes
        val FOLLOW_UP_DELAY = 30.seconds
    }
}
