package co.electriccoin.zcash.ui.screen.home.balancechart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.common.pricing.usecase.ObservePortfolioHistoryUseCase
import co.electriccoin.zcash.ui.common.pricing.usecase.PortfolioHistoryState
import co.electriccoin.zcash.ui.common.provider.IsPortfolioChartEnabledProvider
import co.electriccoin.zcash.ui.common.usecase.BalanceHistory
import co.electriccoin.zcash.ui.common.usecase.BalanceHistoryPoint
import co.electriccoin.zcash.ui.common.usecase.GetBalanceHistoryUseCase
import co.electriccoin.zcash.ui.design.component.chart.SparkChartData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BalanceChartVM(
    getBalanceHistory: GetBalanceHistoryUseCase,
    isChartEnabled: IsPortfolioChartEnabledProvider,
    observePortfolioHistory: ObservePortfolioHistoryUseCase,
) : ViewModel() {
    private val selectedPeriod = MutableStateFlow(BalanceChartPeriod.DEFAULT)
    private val balanceHistory =
        getBalanceHistory
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    val state: StateFlow<BalanceChartState> =
        combine(isChartEnabled.observe(), balanceHistory, selectedPeriod) { enabled, history, period ->
            createChartRequest(enabled = enabled, history = history, period = period)
        }.distinctUntilChanged()
            .flatMapLatest { request ->
                when (request) {
                    ChartRequest.Hidden -> {
                        flowOf(BalanceChartState.Hidden)
                    }

                    ChartRequest.Loading -> {
                        flowOf(BalanceChartState.Loading)
                    }

                    is ChartRequest.Balance -> {
                        balanceHistory.map { history ->
                            createZecChartState(
                                history = history,
                                period = request.period,
                                onPeriodClick = ::onPeriodClick,
                            )
                        }
                    }

                    is ChartRequest.Portfolio -> {
                        observePortfolioHistory
                            .observe(balanceHistory, request.period)
                            .map { it.toChartState(request.period) }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = BalanceChartState.Loading,
            )

    private fun createChartRequest(
        enabled: Boolean,
        history: BalanceHistory?,
        period: BalanceChartPeriod,
    ): ChartRequest =
        when {
            !enabled -> {
                ChartRequest.Balance(period)
            }

            history == null -> {
                ChartRequest.Loading
            }

            history !is BalanceHistory.Reconciled -> {
                ChartRequest.Hidden
            }

            history.confirmedBalance.value <= 0L -> {
                ChartRequest.Hidden
            }

            history.points.none { it.balance.value > 0L } -> {
                ChartRequest.Hidden
            }

            else -> {
                ChartRequest.Portfolio(period)
            }
        }

    private fun PortfolioHistoryState.toChartState(period: BalanceChartPeriod): BalanceChartState =
        when (this) {
            PortfolioHistoryState.Loading -> {
                BalanceChartState.Loading
            }

            PortfolioHistoryState.Empty -> {
                BalanceChartState.Empty(selectedPeriod = period, onPeriodClick = ::onPeriodClick)
            }

            PortfolioHistoryState.Unavailable -> {
                BalanceChartState.Hidden
            }

            is PortfolioHistoryState.Data -> {
                BalanceChartState.Data(
                    fiatCurrency = fiatCurrency,
                    chart = chart,
                    absoluteChangeFiat = absoluteChangeFiat,
                    percentageChange = percentageChange,
                    availableFrom = availableFrom,
                    dataAsOf = dataAsOf,
                    isStale = isStale,
                    selectedPeriod = period,
                    onPeriodClick = ::onPeriodClick,
                )
            }
        }

    private fun onPeriodClick(period: BalanceChartPeriod) = selectedPeriod.update { period }
}

private sealed interface ChartRequest {
    data object Hidden : ChartRequest

    data object Loading : ChartRequest

    data class Balance(
        val period: BalanceChartPeriod,
    ) : ChartRequest

    data class Portfolio(
        val period: BalanceChartPeriod,
    ) : ChartRequest
}

internal fun createZecChartState(
    history: BalanceHistory?,
    period: BalanceChartPeriod,
    now: Instant = Instant.now(),
    onPeriodClick: (BalanceChartPeriod) -> Unit = {},
): BalanceChartState {
    return when {
        history == null -> BalanceChartState.Loading
        history !is BalanceHistory.Reconciled -> BalanceChartState.Hidden
        history.confirmedBalance.value <= 0L -> BalanceChartState.Hidden
        history.points.none { it.balance.value > 0L } -> BalanceChartState.Hidden
        else -> {
            val windowed = windowForPeriod(history.points, period, now)
            if (windowed.size < MIN_POINTS_FOR_CHART) {
                BalanceChartState.Empty(period, onPeriodClick)
            } else {
                BalanceChartState.ZecData(
                    chart =
                        SparkChartData(
                            points =
                                windowed.map { point ->
                                    SparkChartData.Point(
                                        x = point.timestamp.epochSecond.toDouble(),
                                        y = point.balance.value.toDouble(),
                                    )
                                }
                        ),
                    selectedPeriod = period,
                    onPeriodClick = onPeriodClick,
                )
            }
        }
    }
}

private fun windowForPeriod(
    history: List<BalanceHistoryPoint>,
    period: BalanceChartPeriod,
    now: Instant,
): List<BalanceHistoryPoint> {
    val window = period.window ?: return extendToNow(history, now)
    val cutoff = now.minus(window)
    val inWindow = history.filter { !it.timestamp.isBefore(cutoff) }
    val lastBefore = history.lastOrNull { it.timestamp.isBefore(cutoff) }
    val withBaseline =
        if (lastBefore == null) {
            inWindow
        } else {
            listOf(lastBefore.copy(timestamp = cutoff)) + inWindow
        }
    return extendToNow(withBaseline, now)
}

private fun extendToNow(
    points: List<BalanceHistoryPoint>,
    now: Instant,
): List<BalanceHistoryPoint> {
    val last = points.lastOrNull() ?: return emptyList()
    return if (last.timestamp.isBefore(now)) points + last.copy(timestamp = now) else points
}

private const val MIN_POINTS_FOR_CHART = 2
