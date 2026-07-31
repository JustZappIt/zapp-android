package co.electriccoin.zcash.ui.screen.home.balancechart

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.component.chart.SparkChartData

sealed interface BalanceChartState {
    data object Loading : BalanceChartState

    /** Wallet has no transactions at all — widget should not be rendered. */
    data object Hidden : BalanceChartState

    data class Empty(
        val selectedPeriod: BalanceChartPeriod,
        val onPeriodClick: (BalanceChartPeriod) -> Unit,
    ) : BalanceChartState

    data class Data(
        val chart: SparkChartData,
        val periodEndBalance: Zatoshi,
        val selectedPeriod: BalanceChartPeriod,
        val onPeriodClick: (BalanceChartPeriod) -> Unit,
    ) : BalanceChartState
}
