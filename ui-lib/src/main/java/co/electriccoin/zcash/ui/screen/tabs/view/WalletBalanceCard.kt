package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import co.electriccoin.zcash.ui.design.component.chart.SparkChartSelection
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.balances.LocalBalancesAvailable
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.balances.ShieldBreakdownState
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartPeriod
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartState
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.absoluteValue
import co.electriccoin.zcash.ui.design.R as DesignR

@Composable
internal fun BalanceCard(
    balanceState: BalanceWidgetState,
    chartState: BalanceChartState,
    zecUsdPrice: BigDecimal? = null,
    showZecAsPrimary: Boolean? = null,
    onToggleBalanceDisplay: (() -> Unit)? = null,
    onToggleBalanceVisibility: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasBalance = balanceState.totalBalance.value > 0L
    val balancesAvailable = LocalBalancesAvailable.current

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
            onToggleBalanceVisibility = onToggleBalanceVisibility,
        )

        if (balancesAvailable && hasBalance && chartState !is BalanceChartState.Hidden) {
            Spacer(Modifier.height(10.dp))
            if (chartState is BalanceChartState.Data) {
                BalanceDelta(chartState = chartState)
                Spacer(Modifier.height(14.dp))
            }
            ChartContent(state = chartState)
            when (chartState) {
                is BalanceChartState.Data -> {
                    Spacer(Modifier.height(14.dp))
                    PeriodSelector(
                        selectedPeriod = chartState.selectedPeriod,
                        onClick = chartState.onPeriodClick,
                    )
                }

                is BalanceChartState.ZecData -> {
                    Spacer(Modifier.height(14.dp))
                    PeriodSelector(
                        selectedPeriod = chartState.selectedPeriod,
                        onClick = chartState.onPeriodClick,
                    )
                }

                is BalanceChartState.Empty -> {
                    Spacer(Modifier.height(14.dp))
                    PeriodSelector(
                        selectedPeriod = chartState.selectedPeriod,
                        onClick = chartState.onPeriodClick,
                    )
                }

                BalanceChartState.Loading,
                BalanceChartState.Hidden -> {
                    Unit
                }
            }
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
    dotColor: Color,
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
    onToggleBalanceVisibility: (() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val isBalanceHidden = LocalBalancesAvailable.current.not()
    val hiddenBalance = stringResource(DesignR.string.hide_balance_placeholder)
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
    val zecHero = @Composable { showVisibilityButton: Boolean ->
        AnimatedContent(
            targetState = zec,
            transitionSpec = { heroTickerTransition() },
            label = "zecHero",
        ) { zecText ->
            val displayText = rememberScrambledBalanceText(zecText, hiddenBalance, isBalanceHidden)
            Row(modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = displayText,
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
                if (showVisibilityButton && onToggleBalanceVisibility != null) {
                    BalanceVisibilityButton(
                        isHidden = isBalanceHidden,
                        onClick = onToggleBalanceVisibility,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
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
                zecHero(false)
                Spacer(Modifier.height(2.dp))
                SecondaryBalanceLine(
                    clearText = "${fiat.whole}${fiat.fraction}",
                    hiddenText = hiddenBalance,
                    isHidden = isBalanceHidden,
                    style = captionStyle,
                    onToggleBalanceVisibility = onToggleBalanceVisibility,
                )
            } else {
                AnimatedContent(
                    targetState = fiat.whole to fiat.fraction,
                    transitionSpec = { heroTickerTransition() },
                    label = "fiatHero",
                ) { (whole, fraction) ->
                    val displayWhole = rememberScrambledBalanceText(whole, hiddenBalance, isBalanceHidden)
                    val displayFraction = rememberScrambledBalanceText(fraction, "", isBalanceHidden)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        BasicText(
                            text = displayWhole,
                            style =
                                wholeStyle.copy(
                                    lineHeight = TextUnit.Unspecified,
                                    letterSpacing = TextUnit.Unspecified,
                                ),
                            maxLines = 1,
                            softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 52.sp),
                            modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                        )
                        BasicText(text = displayFraction, style = fractionStyle, modifier = Modifier.alignByBaseline())
                    }
                }
                Spacer(Modifier.height(2.dp))
                SecondaryBalanceLine(
                    clearText = "$zec ZEC",
                    hiddenText = "$hiddenBalance ZEC",
                    isHidden = isBalanceHidden,
                    style = captionStyle,
                    onToggleBalanceVisibility = onToggleBalanceVisibility,
                )
            }
        }
    } else {
        zecHero(true)
    }
}

@Composable
private fun SecondaryBalanceLine(
    clearText: String,
    hiddenText: String,
    isHidden: Boolean,
    style: TextStyle,
    onToggleBalanceVisibility: (() -> Unit)?,
) {
    val displayText = rememberScrambledBalanceText(clearText, hiddenText, isHidden)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(text = displayText, style = style, maxLines = 1)
        onToggleBalanceVisibility?.let { onToggle ->
            BalanceVisibilityButton(
                isHidden = isHidden,
                onClick = onToggle,
            )
        }
    }
}

@Composable
private fun BalanceVisibilityButton(
    isHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        stringResource(
            if (isHidden) R.string.show_balances_content_description else R.string.hide_balances_content_description,
        )
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ).semantics {
                    role = Role.Button
                    contentDescription = description
                },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter =
                painterResource(
                    if (isHidden) {
                        DesignR.drawable.ic_app_bar_balances_hide
                    } else {
                        DesignR.drawable.ic_app_bar_balances_show
                    },
                ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ZappTheme.colors.textMuted),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun rememberScrambledBalanceText(
    clearText: String,
    hiddenText: String,
    isHidden: Boolean,
): String {
    var displayedText by
        remember(clearText, hiddenText) {
            mutableStateOf(if (isHidden) hiddenText else clearText)
        }
    var previousHiddenState by remember(clearText, hiddenText) { mutableStateOf(isHidden) }

    LaunchedEffect(clearText, hiddenText, isHidden) {
        val visibilityChanged = previousHiddenState != isHidden
        previousHiddenState = isHidden
        if (!visibilityChanged) {
            displayedText = if (isHidden) hiddenText else clearText
            return@LaunchedEffect
        }

        repeat(SCRAMBLE_FRAME_COUNT) { frame ->
            displayedText = scrambledBalanceFrame(clearText, frame, revealing = !isHidden)
            delay(SCRAMBLE_FRAME_DELAY_MS.toLong())
        }
        displayedText = if (isHidden) hiddenText else clearText
    }

    return displayedText
}

internal fun scrambledBalanceFrame(
    clearText: String,
    frame: Int,
    revealing: Boolean,
): String {
    val digitCount = clearText.count(Char::isDigit)
    if (digitCount == 0) return clearText

    val boundedFrame = frame.coerceIn(0, SCRAMBLE_FRAME_COUNT - 1)
    val transitionedDigits =
        if (revealing) {
            boundedFrame * digitCount / (SCRAMBLE_FRAME_COUNT - 1)
        } else {
            ((boundedFrame + 1) * digitCount + SCRAMBLE_FRAME_COUNT - 1) / SCRAMBLE_FRAME_COUNT
        }
    var digitIndex = 0
    return buildString(clearText.length) {
        clearText.forEachIndexed { characterIndex, character ->
            if (!character.isDigit()) {
                append(character)
            } else {
                val showClearCharacter =
                    if (revealing) digitIndex < transitionedDigits else digitIndex >= transitionedDigits
                append(
                    if (showClearCharacter) {
                        character
                    } else {
                        SCRAMBLE_GLYPHS[
                            (characterIndex + boundedFrame * SCRAMBLE_GLYPH_FRAME_OFFSET) % SCRAMBLE_GLYPHS.length
                        ]
                    },
                )
                digitIndex++
            }
        }
    }
}

@Composable
private fun BalanceDelta(
    chartState: BalanceChartState,
) {
    val c = ZappTheme.colors
    val delta = remember(chartState) { chartState.computeDelta() } ?: return

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
    }
}

@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(3.dp).background(color, RectangleShape))
}

@Composable
private fun ChartContent(state: BalanceChartState) {
    when (state) {
        is BalanceChartState.Data -> ChartArea(state)
        is BalanceChartState.ZecData -> ZecChartArea(state)
        is BalanceChartState.Empty -> ChartMessage(R.string.home_balance_chart_empty)
        BalanceChartState.Loading -> ChartLoading()
        BalanceChartState.Hidden -> Unit
    }
}

@Composable
private fun ZecChartArea(
    state: BalanceChartState.ZecData,
) {
    val c = ZappTheme.colors
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val period = state.selectedPeriod.label()
    val summary = stringResource(R.string.home_balance_chart_accessibility, period, "ZEC")
    val scrubHint = stringResource(R.string.home_balance_chart_scrub_hint)
    val selectionFormatter =
        remember(locale, context) {
            val date =
                DateTimeFormatter
                    .ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .withZone(ZoneOffset.UTC)
            val formatPoint: (SparkChartData.Point) -> SparkChartSelection = { point ->
                val amount = stringRes(Zatoshi(point.y.toLong())).getString(context)
                val day = date.format(Instant.ofEpochSecond(point.x.toLong()))
                SparkChartSelection(
                    primary = amount,
                    secondary = day,
                    contentDescription =
                        context.getString(
                            R.string.home_balance_chart_selection_accessibility,
                            amount,
                            day,
                        ),
                )
            }
            formatPoint
        }
    SparkChart(
        data = state.chart,
        lineColor = c.accent,
        fillColor = c.accent,
        selectionFormatter = selectionFormatter,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(BALANCE_CHART_READY_TEST_TAG)
                .semantics { contentDescription = "$summary. $scrubHint" },
    )
}

@Composable
private fun ChartMessage(
    @StringRes messageRes: Int,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .testTag(BALANCE_CHART_READY_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(messageRes),
            style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textSubtle),
        )
    }
}

@Composable
private fun ChartLoading() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(ZappTheme.colors.surfaceAlt, RectangleShape),
    )
}

@Composable
private fun ChartArea(
    state: BalanceChartState.Data,
) {
    val c = ZappTheme.colors
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val period = state.selectedPeriod.label()
    val delta = state.computeDelta()
    val summary =
        stringResource(
            R.string.home_balance_chart_accessibility,
            period,
            delta?.let { "${it.valueText}, ${it.percentText}" }.orEmpty(),
        )
    val scrubHint = stringResource(R.string.home_balance_chart_scrub_hint)
    val selectionFormatter =
        remember(locale, context, state.fiatCurrency) {
            val fiat =
                NumberFormat.getCurrencyInstance(locale).apply {
                    currency = Currency.getInstance(state.fiatCurrency.code)
                    minimumFractionDigits = 2
                    maximumFractionDigits = 2
                }
            val date =
                DateTimeFormatter
                    .ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .withZone(ZoneOffset.UTC)
            val formatPoint: (SparkChartData.Point) -> SparkChartSelection = { point ->
                val amount = fiat.format(BigDecimal.valueOf(point.y))
                val day = date.format(Instant.ofEpochSecond(point.x.toLong()))
                SparkChartSelection(
                    primary = amount,
                    secondary = day,
                    contentDescription =
                        context.getString(
                            R.string.home_balance_chart_selection_accessibility,
                            amount,
                            day,
                        ),
                )
            }
            formatPoint
        }
    SparkChart(
        data = state.chart,
        lineColor = c.accent,
        fillColor = c.accent,
        selectionFormatter = selectionFormatter,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(BALANCE_CHART_READY_TEST_TAG)
                .semantics { contentDescription = "$summary. $scrubHint" },
    )
}

private const val BALANCE_CHART_READY_TEST_TAG = "balance_chart_ready"

@Composable
private fun PeriodSelector(
    selectedPeriod: BalanceChartPeriod,
    onClick: (BalanceChartPeriod) -> Unit,
) {
    val c = ZappTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth().semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xxs, Alignment.End),
    ) {
        BalanceChartPeriod.entries.forEach { period ->
            val isSelected = period == selectedPeriod
            Box(
                modifier =
                    Modifier
                        .height(ZappTheme.spacing.xl4)
                        .defaultMinSize(minWidth = ZappTheme.spacing.xl5)
                        .background(if (isSelected) c.accentSoft else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClick(period) }
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }.padding(horizontal = ZappTheme.spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = period.label(),
                    style =
                        ZappTheme.typography.groupLabel.copy(
                            color = if (isSelected) c.accentText else c.textSubtle,
                        ),
                )
            }
        }
    }
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
private const val SCRAMBLE_FRAME_COUNT = 8
private const val SCRAMBLE_FRAME_DELAY_MS = ZappMotion.REVEAL_MS / SCRAMBLE_FRAME_COUNT
private const val SCRAMBLE_GLYPH_FRAME_OFFSET = 3
private const val SCRAMBLE_GLYPHS = "#%&?*+=§"

private data class BalanceDeltaResult(
    val valueText: String,
    val percentText: String,
    val isPositive: Boolean,
)

private fun BalanceChartState.computeDelta(): BalanceDeltaResult? {
    if (this !is BalanceChartState.Data) return null
    val formatter =
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            currency = Currency.getInstance(fiatCurrency.code)
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    val valueText = formatter.format(absoluteChangeFiat.abs())
    val sign = if (percentageChange.signum() >= 0) "+" else "-"
    val percentText = "$sign${percentageChange.abs().setScale(2, RoundingMode.HALF_UP).toPlainString()}%"
    return BalanceDeltaResult(
        valueText = valueText,
        percentText = percentText,
        isPositive = percentageChange.signum() >= 0,
    )
}

@Composable
private fun BalanceChartPeriod.label(): String = stringResource(labelRes)
