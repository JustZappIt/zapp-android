package co.electriccoin.zcash.ui.screen.invest.common

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

/**
 * The frame every Invest screen shares, after BridgeToBaseView: a display title with an optional info button, a
 * scrolling body, and the bottom action bar (back on the left, the primary action on the right).
 */
@Composable
internal fun InvestScreenFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    primaryButton: ButtonState? = null,
    isPrimaryLoading: Boolean = false,
    onInfo: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ZappTheme.colors
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
                    .padding(horizontal = INVEST_HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = title,
                    style = ZappTheme.typography.display.copy(color = c.text),
                    modifier = Modifier.weight(1f),
                )
                onInfo?.let { InvestInfoButton(onClick = it) }
            }
            Spacer(Modifier.height(INVEST_GAP_LG.dp))
            content()
        }
        ZappBottomActionBar(
            onBack = onBack,
            primaryAction =
                primaryButton?.let { button ->
                    { InvestPrimaryButton(button, isPrimaryLoading) }
                },
        )
    }
}

@Composable
private fun RowScope.InvestPrimaryButton(
    button: ButtonState,
    isLoading: Boolean,
) {
    ZappButton(
        text = button.text.getValue(),
        enabled = button.isEnabled && !isLoading,
        loading = isLoading,
        variant = ZappButtonVariant.Primary,
        modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
        onClick = button.onClick,
    )
}

@Composable
internal fun InvestInfoButton(onClick: () -> Unit) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .size(INFO_TOUCH_SIZE.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.accent),
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
            contentDescription = stringResource(R.string.invest_info_content_description),
            tint = c.textMuted,
            modifier = Modifier.size(INFO_ICON_SIZE.dp),
        )
    }
}

/** A square two-letter avatar standing in for a company logo (decision 7: monograms, no logos). */
@Composable
internal fun InvestMonogram(
    text: String,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Box(
        modifier = modifier.size(MONOGRAM_SIZE.dp).background(c.accentSoft, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = ZappTheme.typography.chip.copy(color = c.accentText, fontWeight = FontWeight.Bold),
        )
    }
}

/** One stock line: monogram, company name over ticker, and a right-hand value stack. */
@Composable
internal fun InvestAssetRow(
    monogram: String,
    name: String,
    ticker: String,
    primaryValue: String,
    secondaryValue: String?,
    modifier: Modifier = Modifier,
    isPrimaryMuted: Boolean = false,
    horizontalPadding: Dp = INVEST_HORIZONTAL_PADDING.dp,
    onClick: (() -> Unit)? = null,
) {
    val c = ZappTheme.colors
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
                ).padding(horizontal = horizontalPadding, vertical = ROW_VERTICAL_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
    ) {
        InvestMonogram(monogram)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = name,
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            BasicText(text = ticker, style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted))
        }
        Column(horizontalAlignment = Alignment.End) {
            BasicText(
                text = primaryValue,
                style = ZappTheme.typography.rowTitle.copy(color = if (isPrimaryMuted) c.textMuted else c.text),
                maxLines = 1,
            )
            secondaryValue?.let {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = it,
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = 1,
                )
            }
        }
    }
}

/** A tappable square checkbox with its sentence, in Zapp's flat style. */
@Composable
internal fun InvestCheckboxRow(
    text: String,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Checkbox
                    stateDescription = if (isChecked) "checked" else "unchecked"
                }.padding(vertical = CHECKBOX_VERTICAL_PADDING.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(CHECKBOX_SIZE.dp)
                    .background(if (isChecked) c.accent else Color.Transparent, RectangleShape)
                    .border(BorderStroke(1.dp, if (isChecked) c.accent else c.borderStrong), RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isChecked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = c.onAccent,
                    modifier = Modifier.size(CHECK_ICON_SIZE.dp),
                )
            }
        }
        BasicText(text = text, style = ZappTheme.typography.body.copy(color = c.text), modifier = Modifier.weight(1f))
    }
}

/**
 * A flat notice with an accent (or danger) rule on the left, like the settlement ledger's notice: the Tor and
 * market-hours banners and the no-price card. [actions] sit under the text.
 */
@Composable
internal fun InvestNotice(
    body: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    isDanger: Boolean = false,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(if (isDanger) c.dangerSoft else c.surface, RectangleShape)
                .border(BorderStroke(1.dp, if (isDanger) c.danger else c.border), RectangleShape),
    ) {
        Box(
            Modifier
                .width(NOTICE_RULE_WIDTH.dp)
                .fillMaxHeight()
                .background(if (isDanger) c.danger else c.accent, RectangleShape),
        )
        Column(
            modifier = Modifier.weight(1f).padding(NOTICE_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(INVEST_GAP_SM.dp),
        ) {
            title?.let {
                BasicText(
                    text = it,
                    style = ZappTheme.typography.rowTitle.copy(color = if (isDanger) c.danger else c.text),
                )
            }
            BasicText(text = body, style = ZappTheme.typography.caption.copy(color = c.textMuted))
            actions?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = INVEST_GAP_SM.dp),
                    horizontalArrangement = Arrangement.spacedBy(INVEST_GAP_SM.dp),
                    content = it,
                )
            }
        }
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

internal const val INVEST_HORIZONTAL_PADDING = 18
internal const val INVEST_GAP_SM = 8
internal const val INVEST_GAP_MD = 12
internal const val INVEST_GAP_LG = 20
private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12
private const val INFO_TOUCH_SIZE = 32
private const val INFO_ICON_SIZE = 20
private const val MONOGRAM_SIZE = 40
private const val ROW_VERTICAL_PADDING = 12
private const val ROW_GAP = 14
private const val CHECKBOX_SIZE = 20
private const val CHECK_ICON_SIZE = 14
private const val CHECKBOX_VERTICAL_PADDING = 8
private const val NOTICE_RULE_WIDTH = 3
private const val NOTICE_PADDING = 14
