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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import xyz.justzappit.offramp.atomicswap.SwapDirection
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Holds the process up while a conversion is under way, as a foreground service when Android allows one. */
@Keep
class AtomicSwapWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters),
    KoinComponent {
    private val repository: AtomicSwapRepository by inject()
    private val reverse: ReverseSwapRepository by inject()
    private val notifier: AtomicSwapNotifier by inject()

    override suspend fun doWork(): Result {
        if (!repository.isUnderWay() && !reverse.isUnderWay()) return Result.success()
        val isForeground = promote(applicationContext.getString(R.string.convert_progress_title))
        repository.resume(isForeground = false)
        reverse.resume(isForeground = false)
        val settled =
            coroutineScope {
                val updates = if (isForeground) launch { keepNotificationCurrent() } else null
                withTimeoutOrNull(if (isForeground) FOREGROUND_BUDGET else BACKGROUND_BUDGET) {
                    val reverseWait = launch { reverse.awaitSettled() }
                    repository.awaitSettled()
                    reverseWait.join()
                }.also { updates?.cancel() }
            } != null
        if (!settled) AtomicSwapScheduler(applicationContext).runAfter(FOLLOW_UP_DELAY)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.foregroundInfo(applicationContext.getString(R.string.convert_progress_title), SwapDirection.FORWARD)

    private suspend fun keepNotificationCurrent() {
        combine(repository.state, reverse.state) { forward, reverse ->
            val converting = reverse.record?.takeIf { it.underWay }
            converting?.phase?.label() ?: AtomicSwapStage.of(forward).label
        }.distinctUntilChanged().collect { label -> promote(applicationContext.getString(label)) }
    }

    private suspend fun promote(text: String): Boolean {
        val converting = reverse.state.value.record
        val direction = if (converting?.underWay == true) SwapDirection.REVERSE else SwapDirection.FORWARD
        return try {
            setForeground(notifier.foregroundInfo(text, direction))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Twig.info { "Atomic swap: no foreground service from here, ${e.message}" }
            false
        } catch (e: SecurityException) {
            Twig.info { "Atomic swap: no foreground service in this build, ${e.message}" }
            false
        }
    }

    private companion object {
        val FOREGROUND_BUDGET = 3.hours

        // Android stops a worker that isn't a foreground service after ten minutes.
        val BACKGROUND_BUDGET = 9.minutes
        val FOLLOW_UP_DELAY = 30.seconds
    }
}
