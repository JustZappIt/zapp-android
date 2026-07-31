package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.component.chart.SparkChart
import co.electriccoin.zcash.ui.design.component.chart.SparkChartData
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegmentedSelector
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.balances.ShieldBreakdownState
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartPeriod
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartState
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.absoluteValue

@Composable
internal fun BalanceCard(
    balanceState: BalanceWidgetState,
    chartState: BalanceChartState,
    zecUsdPrice: BigDecimal? = null,
    showZecAsPrimary: Boolean? = null,
    onToggleBalanceDisplay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val hasBalance = balanceState.totalBalance.value > 0L

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
    ) {
        BalanceSectionLabel(onBalanceClick = balanceState.onBalanceClick)

        BalanceAmount(
            balanceState = balanceState,
            zecUsdPrice = zecUsdPrice,
            showZecAsPrimary = showZecAsPrimary,
            onToggleBalanceDisplay = onToggleBalanceDisplay,
        )

        if (hasBalance && chartState is BalanceChartState.Data) {
            Spacer(Modifier.height(10.dp))
            BalanceDelta(chartState = chartState)
            Spacer(Modifier.height(14.dp))
            ChartArea(state = chartState)
            Spacer(Modifier.height(14.dp))
            PeriodSelector(state = chartState)
        }

        balanceState.breakdown?.let { breakdown ->
            Spacer(Modifier.height(20.dp))
            BalanceBreakdownSection(state = breakdown)
        }
    }
}

/**
 * The section label doubles as the entry point to the per-pool breakdown. The balance figure
 * itself already owns a tap (it toggles ZEC/fiat), so the pool sheet hangs off the label rather
 * than stealing that gesture. The row absorbs the card's leading padding and the gap above the
 * amount so the tap target clears 40dp without shifting the layout.
 */
@Composable
private fun BalanceSectionLabel(onBalanceClick: (() -> Unit)?) {
    val c = ZappTheme.colors
    val label = stringResource(R.string.home_balance_total_label)
    val spacing = Modifier.padding(top = 18.dp, bottom = 8.dp)
    if (onBalanceClick == null) {
        ZappSectionLabel(text = label, modifier = spacing)
        return
    }
    val description = stringResource(R.string.home_balance_pools_content_description)
    Row(
        modifier =
            Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBalanceClick,
                ).semantics {
                    role = Role.Button
                    contentDescription = description
                }.then(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZappSectionLabel(text = label)
        Spacer(Modifier.width(6.dp))
        BasicText(
            text = "›",
            style = ZappTheme.typography.groupLabel.copy(color = c.textSubtle),
        )
    }
}

@Composable
private fun BalanceBreakdownSection(
    state: ShieldBreakdownState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val shielded =
        stringRes(state.shieldedBalance) orHiddenString
            stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    val transparent =
        stringRes(state.transparentBalance) orHiddenString
            stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border, RectangleShape))
        Spacer(Modifier.height(14.dp))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { state.onBreakdownClick() },
        ) {
            BreakdownLine(
                label = stringResource(R.string.home_balance_breakdown_shielded),
                amount = shielded,
                dotColor = c.accent,
            )
            Spacer(Modifier.height(8.dp))
            BreakdownLine(
                label = stringResource(R.string.home_balance_breakdown_transparent),
                amount = transparent,
                dotColor = c.textSubtle,
            )
        }
        Spacer(Modifier.height(14.dp))
        ZappButton(
            text = stringResource(R.string.balance_action_shield),
            variant = ZappButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = state.onShieldClick,
        )
    }
}

@Composable
private fun BreakdownLine(
    label: String,
    amount: String,
    dotColor: androidx.compose.ui.graphics.Color,
) {
    val c = ZappTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(dotColor, RectangleShape))
        Spacer(Modifier.width(8.dp))
        BasicText(
            text = label,
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            text = amount,
            style = ZappTheme.typography.body.copy(color = c.text, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun BalanceAmount(
    balanceState: BalanceWidgetState,
    zecUsdPrice: BigDecimal?,
    showZecAsPrimary: Boolean? = null,
    onToggleBalanceDisplay: (() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val fiat = balanceState.formattedFiat(zecUsdPrice)
    val context = LocalContext.current
    val zec =
        remember(balanceState.totalBalance, context) {
            stringRes(balanceState.totalBalance, TickerLocation.HIDDEN).getString(context)
        }

    // Swiss display style — Black weight, oversized, tight tracking — matches
    // the wallet hero in the design canvas (52sp whole / 26sp fraction).
    val wholeStyle =
        ZappTheme.typography.display.copy(
            color = c.text,
            fontSize = 52.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-3).sp,
        )
    val fractionStyle =
        ZappTheme.typography.displaySecondary.copy(
            color = c.textMuted,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )

    val captionStyle = ZappTheme.typography.caption.copy(color = c.textMuted)
    val tickerStyle =
        ZappTheme.typography.displaySecondary.copy(
            color = c.textMuted,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Light,
        )

    // Null the absolute lineHeight/letterSpacing so the line box and tracking scale with autoSize.
    // Ticker-style transition: the hero slides up a third of its height when the balance changes.
    val zecHero = @Composable {
        AnimatedContent(
            targetState = zec,
            transitionSpec = { heroTickerTransition() },
            label = "zecHero",
        ) { zecText ->
            Row {
                BasicText(
                    text = zecText,
                    style = wholeStyle.copy(lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 52.sp),
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                BasicText(
                    text = "ZEC",
                    style = tickerStyle,
                    modifier = Modifier.alignByBaseline().padding(start = 6.dp),
                )
            }
        }
    }

    if (fiat != null) {
        var localShowZecAsPrimary by rememberSaveable { mutableStateOf(false) }
        val showZec = showZecAsPrimary ?: localShowZecAsPrimary
        val onToggle = onToggleBalanceDisplay ?: { localShowZecAsPrimary = !localShowZecAsPrimary }
        Column(
            modifier =
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggle() },
        ) {
            if (showZec) {
                zecHero()
                Spacer(Modifier.height(2.dp))
                BasicText(text = "${fiat.whole}${fiat.fraction}", style = captionStyle)
            } else {
                AnimatedContent(
                    targetState = fiat.whole to fiat.fraction,
                    transitionSpec = { heroTickerTransition() },
                    label = "fiatHero",
                ) { (whole, fraction) ->
                    Row {
                        BasicText(text = whole, style = wholeStyle, modifier = Modifier.alignByBaseline())
                        BasicText(text = fraction, style = fractionStyle, modifier = Modifier.alignByBaseline())
                    }
                }
                Spacer(Modifier.height(2.dp))
                BasicText(text = "$zec ZEC", style = captionStyle)
            }
        }
    } else {
        zecHero()
    }
}

@Composable
private fun BalanceDelta(chartState: BalanceChartState) {
    val c = ZappTheme.colors
    val chartPoints = if (chartState is BalanceChartState.Data) chartState.chart.points else null
    val delta = remember(chartPoints) { chartState.computeDelta() }
    val periodLabel = chartState.periodOrDefault().label()

    if (delta == null) {
        BasicText(
            text = periodLabel,
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        return
    }

    val sign = if (delta.isPositive) "▲" else "▼"
    val color = if (delta.isPositive) c.success else c.danger

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = "$sign ${delta.valueText}",
            style = ZappTheme.typography.caption.copy(color = color),
        )
        Dot(color = c.textSubtle)
        BasicText(
            text = delta.percentText,
            style = ZappTheme.typography.caption.copy(color = color),
        )
        Dot(color = c.textSubtle)
        BasicText(
            text = periodLabel,
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.size(3.dp).background(color, RectangleShape))
}

@Composable
private fun ChartArea(state: BalanceChartState.Data) {
    val c = ZappTheme.colors
    SparkChart(
        data = state.chart,
        lineColor = c.accent,
        fillColor = c.accent,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PeriodSelector(state: BalanceChartState) {
    val selectedPeriod = state.periodOrDefault()
    val onClick = state.onPeriodClickOrNoop()
    val periods = BalanceChartPeriod.entries
    val labels = periods.map { it.label() }
    val index = periods.indexOf(selectedPeriod).coerceAtLeast(0)

    ZappSegmentedSelector(
        options = labels,
        selectedIndex = index,
        onSelect = { i -> onClick(periods[i]) },
    )
}

private data class FormattedFiat(
    val whole: String,
    val fraction: String
)

@Composable
private fun BalanceWidgetState.formattedFiat(zecUsdPrice: BigDecimal?): FormattedFiat? {
    val rate = zecFiatRate(exchangeRate, zecUsdPrice) ?: return null
    return remember(totalBalance, rate) {
        formatFiat(
            rate.zecToFiat(totalBalance.convertZatoshiToZec()),
            rate.symbol,
        )
    }
}

private fun formatFiat(fiatAmount: BigDecimal, symbol: String): FormattedFiat {
    val scaled = fiatAmount.setScale(2, RoundingMode.HALF_UP)
    val whole = scaled.toBigInteger()
    val fractionCents = scaled.subtract(BigDecimal(whole)).multiply(BigDecimal(100)).toInt()
    return FormattedFiat(
        whole = "$symbol${DecimalFormat("#,###").format(whole)}",
        fraction = ".%02d".format(fractionCents.absoluteValue),
    )
}

private fun heroTickerTransition(): ContentTransform =
    (
        fadeIn(tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing)) +
            slideInVertically(tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing)) {
                it / HERO_TICKER_SLIDE_DIVISOR
            }
    ).togetherWith(fadeOut(tween(ZappMotion.STATE_MS)))

private fun Zatoshi.convertZatoshiToZec(): BigDecimal =
    BigDecimal(value).divide(BigDecimal(100_000_000L), 8, RoundingMode.HALF_UP)

private const val HERO_TICKER_SLIDE_DIVISOR = 3

private data class BalanceDeltaResult(
    val valueText: String,
    val percentText: String,
    val isPositive: Boolean,
)

private fun BalanceChartState.computeDelta(): BalanceDeltaResult? {
    if (this !is BalanceChartState.Data) return null
    val points: List<SparkChartData.Point> = chart.points
    if (points.size < 2) return null
    val first = points.first().y
    val last = points.last().y
    if (first == 0.0) return null
    val deltaZatoshi = last - first
    val percent = (deltaZatoshi / first) * 100.0
    val deltaZec = BigDecimal(deltaZatoshi).divide(BigDecimal(100_000_000L), 6, RoundingMode.HALF_UP)
    val valueText = "${deltaZec.abs().toPlainString()} ZEC"
    val sign = if (percent >= 0) "+" else "-"
    val percentText = "$sign%.2f%%".format(percent.absoluteValue)
    return BalanceDeltaResult(
        valueText = valueText,
        percentText = percentText,
        isPositive = percent >= 0,
    )
}

private fun BalanceChartState.periodOrDefault(): BalanceChartPeriod =
    when (this) {
        is BalanceChartState.Data -> selectedPeriod
        is BalanceChartState.Empty -> selectedPeriod
        BalanceChartState.Loading, BalanceChartState.Hidden -> BalanceChartPeriod.DEFAULT
    }

private fun BalanceChartState.onPeriodClickOrNoop(): (BalanceChartPeriod) -> Unit =
    when (this) {
        is BalanceChartState.Data -> onPeriodClick
        is BalanceChartState.Empty -> onPeriodClick
        BalanceChartState.Loading, BalanceChartState.Hidden -> { _ -> }
    }

@Composable
private fun BalanceChartPeriod.label(): String = stringResource(labelRes)
