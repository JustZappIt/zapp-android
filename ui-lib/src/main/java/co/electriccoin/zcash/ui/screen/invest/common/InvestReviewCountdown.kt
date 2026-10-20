package co.electriccoin.zcash.ui.screen.invest.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Ticks a review sheet's held price down to zero, on [clock]. */
internal class InvestReviewCountdown(
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private var job: Job? = null

    fun remainingSeconds(expiresAt: Instant): Long = (expiresAt - clock.now()).inWholeSeconds.coerceAtLeast(0)

    fun isExpired(expiresAt: Instant): Boolean = clock.now() >= expiresAt

    fun start(
        expiresAt: Instant,
        onTick: (remainingSeconds: Long) -> Unit,
    ) {
        stop()
        job =
            scope.launch {
                var remaining = remainingSeconds(expiresAt)
                while (remaining > 0) {
                    delay(1.seconds)
                    remaining = remainingSeconds(expiresAt)
                    onTick(remaining)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
