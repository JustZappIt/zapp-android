package co.electriccoin.zcash.ui.screen.invest.section

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_HORIZONTAL_PADDING
import co.electriccoin.zcash.ui.screen.invest.common.InvestAssetRow
import co.electriccoin.zcash.ui.screen.invest.common.InvestUpdatedLine

/**
 * The PAY tab's Investments block, between the balance card and Recent activity. Holdings show value first and
 * shares second, and follow the eye button like every other balance on the tab.
 */
internal fun LazyListScope.investmentsSection(state: InvestmentsSectionState) {
    when (state) {
        InvestmentsSectionState.Hidden -> {
            Unit
        }

        is InvestmentsSectionState.Entry -> {
            item(key = "invest_entry") {
                InvestEntryCard(onClick = state.onClick)
                Spacer(Modifier.height(SECTION_BOTTOM_GAP.dp))
            }
        }

        is InvestmentsSectionState.Loading -> {
            item(key = "invest_loading") {
                SectionHeader(onClick = state.onClick)
                LoadingRow()
                Spacer(Modifier.height(SECTION_BOTTOM_GAP.dp))
            }
        }

        is InvestmentsSectionState.Error -> {
            item(key = "invest_error") {
                SectionHeader(onClick = null)
                ErrorRow(state)
                Spacer(Modifier.height(SECTION_BOTTOM_GAP.dp))
            }
        }

        is InvestmentsSectionState.Holdings -> {
            holdingsItems(state)
        }
    }
}

private fun LazyListScope.holdingsItems(state: InvestmentsSectionState.Holdings) {
    item(key = "invest_header") {
        SectionHeader(onClick = state.onHeaderClick)
        InvestUpdatedLine(
            updatedAtEpochMillis = state.updatedAtEpochMillis,
            isStale = state.isStale,
            onRetry = state.onRetry,
            modifier = Modifier.padding(start = HEADER_START.dp, bottom = INVEST_GAP_SM.dp),
        )
    }
    items(items = state.rows, key = { "invest_${it.key}" }) { row ->
        HoldingRow(row)
        ZappRowDivider(inset = true)
    }
    item(key = "invest_total") {
        TotalRow(state)
        Spacer(Modifier.height(SECTION_BOTTOM_GAP.dp))
    }
}

@Composable
private fun SectionHeader(onClick: (() -> Unit)?) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = HEADER_START.dp, end = INVEST_HORIZONTAL_PADDING.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZappSectionLabel(text = stringResource(R.string.invest_section_title), modifier = Modifier.weight(1f))
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textSubtle,
                modifier = Modifier.size(CHEVRON_SIZE.dp),
            )
        }
    }
}

@Composable
private fun HoldingRow(row: InvestHoldingRowState) {
    val hidden = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    InvestAssetRow(
        monogram = row.monogram,
        name = row.name,
        ticker = row.ticker,
        primaryValue = row.value?.let { it orHiddenString hidden } ?: stringResource(R.string.invest_no_price_short),
        secondaryValue = row.units orHiddenString hidden,
        isPrimaryMuted = row.value == null,
        onClick = row.onClick,
    )
}

@Composable
private fun TotalRow(state: InvestmentsSectionState.Holdings) {
    val c = ZappTheme.colors
    val hidden = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = INVEST_HORIZONTAL_PADDING.dp, vertical = TOTAL_VERTICAL_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(R.string.invest_section_total_label),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = state.total?.let { it orHiddenString hidden } ?: stringResource(R.string.invest_no_price_short),
            style = ZappTheme.typography.rowTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = LOADING_PADDING.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = ZappTheme.colors.accent, modifier = Modifier.size(LOADING_SIZE.dp))
    }
}

@Composable
private fun ErrorRow(state: InvestmentsSectionState.Error) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = INVEST_HORIZONTAL_PADDING.dp, vertical = TOTAL_VERTICAL_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = state.message.getValue(),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = stringResource(R.string.invest_retry),
            style = ZappTheme.typography.caption.copy(color = c.accentText, fontWeight = FontWeight.SemiBold),
            modifier =
                Modifier
                    .clickable(onClick = state.onRetry)
                    .semantics { role = Role.Button }
                    .padding(start = INVEST_GAP_SM.dp),
        )
    }
}

/** The one-line card that stands in for the block before the gate and setup, or before the first buy. */
@Composable
private fun InvestEntryCard(onClick: () -> Unit) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .padding(horizontal = INVEST_HORIZONTAL_PADDING.dp)
                .fillMaxWidth()
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = ENTRY_PADDING.dp, vertical = ENTRY_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ENTRY_GAP.dp),
    ) {
        Box(
            modifier = Modifier.size(ENTRY_ICON_BOX.dp).background(c.accentSoft, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(ENTRY_ICON.dp),
            )
        }
        BasicText(
            text = stringResource(R.string.invest_entry_card),
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = c.textSubtle,
            modifier = Modifier.size(CHEVRON_SIZE.dp),
        )
    }
}

private const val HEADER_START = 20
private const val SECTION_BOTTOM_GAP = 20
private const val CHEVRON_SIZE = 18
private const val TOTAL_VERTICAL_PADDING = 12
private const val LOADING_PADDING = 16
private const val LOADING_SIZE = 24
private const val ENTRY_PADDING = 14
private const val ENTRY_GAP = 12
private const val ENTRY_ICON_BOX = 32
private const val ENTRY_ICON = 18

@PreviewScreens
@Composable
private fun PreviewHoldings() {
    ZcashTheme {
        Column(Modifier.background(ZappTheme.colors.bg)) {
            LazyColumn {
                investmentsSection(
                    InvestmentsSectionState.Holdings(
                        rows =
                            listOf(
                                InvestHoldingRowState(
                                    "nvda",
                                    "NV",
                                    "NVIDIA",
                                    "NVDA",
                                    stringRes("$98.99"),
                                    stringRes("0.4410 NVDA")
                                ) {},
                                InvestHoldingRowState(
                                    "spy",
                                    "SP",
                                    "S&P 500 ETF",
                                    "SPY",
                                    stringRes("$99.95"),
                                    stringRes("0.1284 SPY")
                                ) {},
                            ),
                        total = stringRes("$198.94"),
                        updatedAtEpochMillis = System.currentTimeMillis() - 60_000,
                        isStale = false,
                        onHeaderClick = {},
                        onRetry = {},
                    ),
                )
            }
        }
    }
}

@PreviewScreens
@Composable
private fun PreviewEntry() {
    ZcashTheme {
        Column(Modifier.background(ZappTheme.colors.bg)) {
            LazyColumn { investmentsSection(InvestmentsSectionState.Entry {}) }
        }
    }
}
