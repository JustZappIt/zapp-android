package co.electriccoin.zcash.ui.screen.home.balancechart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.common.usecase.BalanceHistoryPoint
import co.electriccoin.zcash.ui.common.usecase.GetBalanceHistoryUseCase
import co.electriccoin.zcash.ui.design.component.chart.SparkChartData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant

class BalanceChartVM(
    getBalanceHistory: GetBalanceHistoryUseCase,
) : ViewModel() {
    private val selectedPeriod = MutableStateFlow(BalanceChartPeriod.DEFAULT)

    val state: StateFlow<BalanceChartState> =
        combine(getBalanceHistory.observe(), selectedPeriod) { history, period ->
            createState(history, period)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = BalanceChartState.Loading,
        )

    private fun createState(
        history: List<BalanceHistoryPoint>?,
        period: BalanceChartPeriod,
    ): BalanceChartState {
        if (history == null) return BalanceChartState.Loading
        if (history.isEmpty()) return BalanceChartState.Hidden

        val windowed = windowForPeriod(history, period)
        if (windowed.size < MIN_POINTS_FOR_CHART) {
            return BalanceChartState.Empty(
                selectedPeriod = period,
                onPeriodClick = ::onPeriodClick,
            )
        }

        val chart =
            SparkChartData(
                points =
                    windowed.map { p ->
                        SparkChartData.Point(
                            x = p.timestamp.epochSecond.toDouble(),
                            y = p.balance.value.toDouble(),
                        )
                    }
            )
        return BalanceChartState.Data(
            chart = chart,
            periodEndBalance = windowed.last().balance,
            selectedPeriod = period,
            onPeriodClick = ::onPeriodClick,
        )
    }

    private fun onPeriodClick(period: BalanceChartPeriod) = selectedPeriod.update { period }

    private fun windowForPeriod(
        history: List<BalanceHistoryPoint>,
        period: BalanceChartPeriod,
    ): List<BalanceHistoryPoint> {
        val window = period.window ?: return extendToNow(history)

        val now = Instant.now()
        val cutoff = now.minus(window)
        val inWindow = history.filter { !it.timestamp.isBefore(cutoff) }
        val lastBefore = history.lastOrNull { it.timestamp.isBefore(cutoff) }

        val withBaseline =
            if (lastBefore != null) {
                listOf(BalanceHistoryPoint(timestamp = cutoff, balance = lastBefore.balance)) + inWindow
            } else {
                inWindow
            }

        if (withBaseline.isEmpty()) return emptyList()
        return extendToNow(withBaseline)
    }

    private fun extendToNow(points: List<BalanceHistoryPoint>): List<BalanceHistoryPoint> {
        if (points.isEmpty()) return points
        val now = Instant.now()
        val last = points.last()
        return if (last.timestamp.isBefore(now)) points + last.copy(timestamp = now) else points
    }
}

private const val MIN_POINTS_FOR_CHART = 2
