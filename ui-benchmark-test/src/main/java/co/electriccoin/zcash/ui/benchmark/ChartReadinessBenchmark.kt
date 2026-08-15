package co.electriccoin.zcash.ui.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMetricApi::class)
class ChartReadinessBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun chatsToReadyPayChart() =
        benchmarkRule.measureRepeated(
            packageName = APP_TARGET_PACKAGE_NAME,
            metrics =
                listOf(
                    TraceSectionMetric(
                        sectionName = CHART_READY_TRACE,
                        mode = TraceSectionMetric.Mode.First,
                    ),
                    FrameTimingMetric(),
                ),
            iterations = 5,
            setupBlock = {
                startActivityAndWait()
                device.wait(Until.findObject(By.res(CHATS_TAB_RESOURCE_ID)), UI_TIMEOUT_MILLIS)?.click()
                device.waitForIdle()
            },
        ) {
            val payTab = device.wait(Until.findObject(By.res(PAY_TAB_RESOURCE_ID)), UI_TIMEOUT_MILLIS)
            checkNotNull(payTab) { "Pay tab was not available; unlock and provision the benchmark wallet first" }
            payTab.click()
            check(device.wait(Until.hasObject(By.res(CHART_READY_RESOURCE_ID)), CHART_TIMEOUT_MILLIS)) {
                "Portfolio chart did not become ready"
            }
        }

    private companion object {
        const val APP_TARGET_PACKAGE_NAME = "xyz.justzappit.zapp"
        const val CHART_READY_TRACE = "BalanceChartReady"
        const val CHATS_TAB_RESOURCE_ID = "zapp_tab_chats"
        const val PAY_TAB_RESOURCE_ID = "zapp_tab_pay"
        const val CHART_READY_RESOURCE_ID = "balance_chart_ready"
        const val UI_TIMEOUT_MILLIS = 10_000L
        const val CHART_TIMEOUT_MILLIS = 30_000L
    }
}
