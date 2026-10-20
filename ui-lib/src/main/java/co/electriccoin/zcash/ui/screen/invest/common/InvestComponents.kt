package co.electriccoin.zcash.ui.screen.invest.common

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappNotice
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

/** Every Invest screen: a display title with an optional "i", a scrolling body, then back and an action. */
@Composable
internal fun InvestScreenFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    primaryButton: ButtonState? = null,
    onInfo: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.gutter, vertical = spacing.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = title,
                    style = ZappTheme.typography.display.copy(color = c.text),
                    modifier = Modifier.weight(1f),
                )
                onInfo?.let {
                    ZappInfoButton(
                        contentDescription = stringResource(R.string.invest_info_content_description),
                        onClick = it,
                    )
                }
            }
            Spacer(Modifier.height(spacing.xl2))
            content()
        }
        ZappBottomActionBar(
            onBack = onBack,
            primaryAction = primaryButton?.let { button -> { InvestPrimaryButton(button) } },
        )
    }
}

@Composable
private fun RowScope.InvestPrimaryButton(button: ButtonState) {
    ZappButton(
        text = button.text.getValue(),
        enabled = button.isEnabled && !button.isLoading,
        loading = button.isLoading,
        variant = ZappButtonVariant.Primary,
        modifier = Modifier.weight(1f).padding(start = ZappTheme.spacing.lg),
        onClick = button.onClick,
    )
}

/** A square two-letter avatar standing in for a company logo. */
@Composable
internal fun InvestMonogram(
    text: String,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Box(
        modifier = modifier.size(MONOGRAM_SIZE).background(c.accentSoft, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = ZappTheme.typography.chip.copy(color = c.accentText, fontWeight = FontWeight.Bold),
        )
    }
}

/** One stock: monogram, name over ticker, and a value stack on the right. */
@Composable
internal fun InvestAssetRow(
    monogram: String,
    name: String,
    ticker: String,
    primaryValue: String,
    secondaryValue: String?,
    modifier: Modifier = Modifier,
    isPrimaryMuted: Boolean = false,
    horizontalPadding: Dp = ZappTheme.spacing.gutter,
    onClick: (() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick).semantics { role = Role.Button }
                    } else {
                        Modifier
                    },
                ).padding(horizontal = horizontalPadding, vertical = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        InvestMonogram(monogram)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = name,
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(spacing.xxs))
            BasicText(text = ticker, style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted))
        }
        Column(horizontalAlignment = Alignment.End) {
            BasicText(
                text = primaryValue,
                style = ZappTheme.typography.rowTitle.copy(color = if (isPrimaryMuted) c.textMuted else c.text),
                maxLines = 1,
            )
            secondaryValue?.let {
                Spacer(Modifier.height(spacing.xxs))
                BasicText(
                    text = it,
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Why Buy or Sell is off, with the way forward. */
@Composable
internal fun InvestTradeInProgressNotice(
    state: InvestTradeInProgressState,
    modifier: Modifier = Modifier,
) {
    ZappNotice(body = state.text.getValue(), modifier = modifier) {
        ZappButton(
            text = state.actionLabel.getValue(),
            variant = ZappButtonVariant.Ghost,
            modifier = Modifier.weight(1f),
            onClick = state.onOpen,
        )
    }
}

/** "Updated 3 minutes ago", or "Couldn't refresh" with a retry link when the figures are stale. */
@Composable
internal fun InvestUpdatedLine(
    updatedAtEpochMillis: Long,
    isStale: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val now = System.currentTimeMillis()
    val updated =
        if (now - updatedAtEpochMillis < DateUtils.MINUTE_IN_MILLIS) {
            stringResource(R.string.invest_updated_just_now)
        } else {
            val ago =
                DateUtils
                    .getRelativeTimeSpanString(updatedAtEpochMillis, now, DateUtils.MINUTE_IN_MILLIS)
                    .toString()
            stringResource(R.string.invest_updated, ago)
        }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(text = updated, style = ZappTheme.typography.caption.copy(color = c.textMuted))
        if (isStale) {
            BasicText(
                text = " · " + stringResource(R.string.invest_couldnt_refresh),
                style = ZappTheme.typography.caption.copy(color = c.danger),
            )
            BasicText(
                text = " · " + stringResource(R.string.invest_retry),
                style = ZappTheme.typography.caption.copy(color = c.accentText, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable(onClick = onRetry).semantics { role = Role.Button },
            )
        }
    }
}

private val MONOGRAM_SIZE = 40.dp
