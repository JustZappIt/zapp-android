package co.electriccoin.zcash.ui.screen.home.balancechart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.ZashiCard
import co.electriccoin.zcash.ui.design.component.chart.SparkChart
import co.electriccoin.zcash.ui.design.component.chart.SparkChartData
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

fun LazyListScope.balanceChartWidget(
    state: BalanceChartState,
    modifier: Modifier = Modifier,
) {
    if (state is BalanceChartState.Hidden) return
    item("balance_chart_widget") {
        Column {
            BalanceChartWidget(state = state, modifier = modifier)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun BalanceChartWidget(
    state: BalanceChartState,
    modifier: Modifier = Modifier,
) {
    if (state is BalanceChartState.Hidden) return

    ZashiCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = stringResource(R.string.home_balance_chart_title),
            color = ZappTheme.colors.text,
            style = ZappTheme.typography.sectionTitle,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(12.dp))

        PeriodSelector(
            selected = state.selectedPeriod,
            onClick = state.onPeriodClick,
        )

        Spacer(Modifier.height(20.dp))

        when (state) {
            is BalanceChartState.Data -> {
                SparkChart(
                    data = state.chart,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Footer(balance = state.periodEndBalance)
            }

            is BalanceChartState.Empty -> {
                EmptyChart()
            }

            BalanceChartState.Loading -> {
                LoadingChart()
            }

            BalanceChartState.Hidden -> {
                Unit
            }
        }
    }
}

private val BalanceChartState.selectedPeriod: BalanceChartPeriod
    get() =
        when (this) {
            is BalanceChartState.Data -> selectedPeriod

            is BalanceChartState.Empty -> selectedPeriod

            BalanceChartState.Loading,
            BalanceChartState.Hidden -> BalanceChartPeriod.DEFAULT
        }

private val BalanceChartState.onPeriodClick: (BalanceChartPeriod) -> Unit
    get() =
        when (this) {
            is BalanceChartState.Data -> onPeriodClick

            is BalanceChartState.Empty -> onPeriodClick

            BalanceChartState.Loading,
            BalanceChartState.Hidden -> { _ -> }
        }

@Composable
private fun PeriodSelector(
    selected: BalanceChartPeriod,
    onClick: (BalanceChartPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BalanceChartPeriod.entries.forEach { period ->
            PeriodChip(
                label = stringResource(period.labelRes),
                isSelected = period == selected,
                onClick = { onClick(period) },
            )
        }
    }
}

@Composable
private fun PeriodChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = ZappTheme.colors.accent
    val bg = if (isSelected) ZappTheme.colors.accentSoft else Color.Transparent
    val fg = if (isSelected) activeColor else ZappTheme.colors.textSubtle

    Text(
        text = label,
        color = fg,
        style = ZappTheme.typography.caption,
        fontWeight = FontWeight.Medium,
        modifier =
            modifier
                .clip(RoundedCornerShape(0.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Footer(balance: Zatoshi) {
    Text(
        text =
            stringResource(
                R.string.home_balance_chart_footer,
                stringRes(balance, TickerLocation.HIDDEN).getValue(),
            ),
        color = ZappTheme.colors.textSubtle,
        style = ZappTheme.typography.caption,
    )
}

@Composable
private fun EmptyChart() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(140.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.home_balance_chart_empty),
            color = ZappTheme.colors.textSubtle,
            style = ZappTheme.typography.caption,
        )
    }
}

@Composable
private fun LoadingChart() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(0.dp))
                .background(ZappTheme.colors.surfaceAlt),
    )
}

object BalanceChartWidgetStateFixture {
    fun data(): BalanceChartState.Data =
        BalanceChartState.Data(
            chart =
                SparkChartData(
                    points =
                        listOf(
                            SparkChartData.Point(0.0, 200_000_000.0),
                            SparkChartData.Point(1.0, 225_000_000.0),
                            SparkChartData.Point(2.0, 220_000_000.0),
                            SparkChartData.Point(3.0, 310_000_000.0),
                            SparkChartData.Point(4.0, 400_000_000.0),
                            SparkChartData.Point(5.0, 260_000_000.0),
                            SparkChartData.Point(6.0, 290_000_000.0),
                        )
                ),
            periodEndBalance = Zatoshi(290_000_000L),
            selectedPeriod = BalanceChartPeriod.W1,
            onPeriodClick = {},
        )
}

@PreviewScreens
@Composable
private fun DataPreview() =
    ZcashTheme {
        ProvideZappTheme {
            BlankSurface {
                BalanceChartWidget(state = BalanceChartWidgetStateFixture.data())
            }
        }
    }

@PreviewScreens
@Composable
private fun EmptyPreview() =
    ZcashTheme {
        ProvideZappTheme {
            BlankSurface {
                BalanceChartWidget(
                    state =
                        BalanceChartState.Empty(
                            selectedPeriod = BalanceChartPeriod.W1,
                            onPeriodClick = {},
                        )
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun LoadingPreview() =
    ZcashTheme {
        ProvideZappTheme {
            BlankSurface {
                BalanceChartWidget(state = BalanceChartState.Loading)
            }
        }
    }
