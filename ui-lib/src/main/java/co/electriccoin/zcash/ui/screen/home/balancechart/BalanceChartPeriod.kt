package co.electriccoin.zcash.ui.screen.home.balancechart

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import java.time.Duration

enum class BalanceChartPeriod(
    @param:StringRes val labelRes: Int,
    val window: Duration?,
) {
    W1(R.string.home_balance_chart_period_1w, Duration.ofDays(7)),
    M1(R.string.home_balance_chart_period_1m, Duration.ofDays(30)),
    Y1(R.string.home_balance_chart_period_1y, Duration.ofDays(YEAR_WINDOW_DAYS)),
    ALL(R.string.home_balance_chart_period_all, null),
    ;

    companion object {
        val DEFAULT = W1
    }
}

private const val YEAR_WINDOW_DAYS = 365L
