package co.electriccoin.zcash.ui.screen.home.balancechart

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.design.component.chart.SparkChartData
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

sealed interface BalanceChartState {
    data object Hidden : BalanceChartState

    data object Loading : BalanceChartState

    data class Data(
        val fiatCurrency: FiatCurrency,
        val chart: SparkChartData,
        val absoluteChangeFiat: BigDecimal,
        val percentageChange: BigDecimal,
        val availableFrom: LocalDate,
        val dataAsOf: Instant,
        val isStale: Boolean,
        val selectedPeriod: BalanceChartPeriod,
        val onPeriodClick: (BalanceChartPeriod) -> Unit,
    ) : BalanceChartState

    /** The original balance-only chart, used when fiat portfolio valuation is disabled. */
    data class ZecData(
        val chart: SparkChartData,
        val selectedPeriod: BalanceChartPeriod,
        val onPeriodClick: (BalanceChartPeriod) -> Unit,
    ) : BalanceChartState

    data class Empty(
        val selectedPeriod: BalanceChartPeriod,
        val onPeriodClick: (BalanceChartPeriod) -> Unit,
    ) : BalanceChartState
}
