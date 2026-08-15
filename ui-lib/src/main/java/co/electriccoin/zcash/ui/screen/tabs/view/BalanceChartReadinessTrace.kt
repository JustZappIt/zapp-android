package co.electriccoin.zcash.ui.screen.tabs.view

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicBoolean

/** Async trace spanning the Pay-tab tap through the first composed chart frame. */
internal object BalanceChartReadinessTrace {
    private val active = AtomicBoolean(false)

    fun begin() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled() && active.compareAndSet(false, true)) {
            Trace.beginAsyncSection(SECTION_NAME, COOKIE)
        }
    }

    fun end() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && active.compareAndSet(true, false)) {
            Trace.endAsyncSection(SECTION_NAME, COOKIE)
        }
    }
}

private const val SECTION_NAME = "BalanceChartReady"
private const val COOKIE = 1
