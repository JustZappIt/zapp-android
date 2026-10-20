package co.electriccoin.zcash.ui.screen.invest.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappNotice
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestAssetRow
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressNotice
import co.electriccoin.zcash.ui.screen.invest.common.InvestUpdatedLine
import co.electriccoin.zcash.ui.screen.invest.section.InvestHoldingRowState

@Composable
internal fun InvestHomeView(state: InvestHomeState) {
    InvestScreenFrame(title = stringResource(R.string.invest_home_title), onBack = state.onBack) {
        state.summary?.let {
            SummaryCard(it)
            Spacer(Modifier.height(ZappTheme.spacing.xl2))
        }
        state.sellOnly?.let {
            InvestTradeInProgressNotice(it)
            Spacer(Modifier.height(ZappTheme.spacing.lg))
        }
        state.recordsUnreadable?.let {
            InvestTradeInProgressNotice(it)
            Spacer(Modifier.height(ZappTheme.spacing.lg))
        }
        state.pendingTrades.forEach { pending ->
            ZappBorderedCard(padding = 0.dp) {
                ZappRow(
                    title = pending.title.getValue(),
                    subtitle = stringResource(R.string.invest_home_pending_subtitle),
                    onClick = pending.onClick,
                )
            }
            Spacer(Modifier.height(ZappTheme.spacing.lg))
        }
        state.torBanner?.let {
            TorBanner(it)
            Spacer(Modifier.height(ZappTheme.spacing.lg))
        }
        state.marketBanner?.let {
            ZappNotice(body = it.getValue())
            Spacer(Modifier.height(ZappTheme.spacing.lg))
        }
        state.marketError?.let {
            ZappNotice(body = it.getValue(), isDanger = true) {
                ZappButton(
                    text = stringResource(R.string.invest_retry),
                    variant = ZappButtonVariant.Ghost,
                    onClick = state.onRetryMarket,
                )
            }
            Spacer(Modifier.height(ZappTheme.spacing.lg))
        }
        state.groups.forEach { group ->
            Spacer(Modifier.height(ZappTheme.spacing.md))
            GroupHeader(group)
            group.rows.forEachIndexed { index, row ->
                StockRow(row)
                if (index != group.rows.lastIndex) ZappRowDivider(inset = true)
            }
            Spacer(Modifier.height(ZappTheme.spacing.lg))
        }
    }
}

@Composable
private fun SummaryCard(summary: InvestHomeSummary) {
    val c = ZappTheme.colors
    val hidden = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    ZappBorderedCard {
        BasicText(
            text = stringResource(R.string.invest_home_your_investments),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(ZappTheme.spacing.xs))
        BasicText(
            text = summary.total?.let { it orHiddenString hidden } ?: stringResource(R.string.invest_no_price_short),
            style = ZappTheme.typography.display.copy(color = c.text),
        )
        InvestUpdatedLine(
            updatedAtEpochMillis = summary.updatedAtEpochMillis,
            isStale = summary.isStale,
            onRetry = summary.onRetry,
        )
        if (summary.rows.isEmpty()) {
            Spacer(Modifier.height(ZappTheme.spacing.md))
            BasicText(
                text = stringResource(R.string.invest_home_no_holdings),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        } else {
            Spacer(Modifier.height(ZappTheme.spacing.md))
            summary.rows.forEach { HoldingLine(it) }
        }
    }
}

@Composable
private fun HoldingLine(row: InvestHoldingRowState) {
    val c = ZappTheme.colors
    val hidden = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZappTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = row.name,
            style = ZappTheme.typography.body.copy(color = c.text),
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            BasicText(
                text = row.value?.let { it orHiddenString hidden } ?: stringResource(R.string.invest_no_price_short),
                style = ZappTheme.typography.body.copy(color = c.text, fontWeight = FontWeight.Medium),
            )
            BasicText(
                text = row.units orHiddenString hidden,
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
        SellAction(row)
    }
}

@Composable
private fun SellAction(row: InvestHoldingRowState) {
    val c = ZappTheme.colors
    val onSell = row.onSell
    val trade = row.tradeInProgress
    when {
        trade != null -> {
            BasicText(
                text = stringResource(if (trade.isSale) R.string.invest_home_selling else R.string.invest_home_buying),
                style = ZappTheme.typography.caption.copy(color = c.accentText),
                modifier =
                    Modifier
                        .padding(start = ZappTheme.spacing.md)
                        .clickable(onClick = trade.onOpen)
                        .semantics { role = Role.Button }
                        .padding(ZappTheme.spacing.md),
            )
        }

        onSell != null -> {
            BasicText(
                text = stringResource(R.string.invest_home_sell),
                style = ZappTheme.typography.caption.copy(color = c.accentText, fontWeight = FontWeight.SemiBold),
                modifier =
                    Modifier
                        .padding(start = ZappTheme.spacing.md)
                        .clickable(onClick = onSell)
                        .semantics { role = Role.Button }
                        .padding(ZappTheme.spacing.md),
            )
        }
    }
}

@Composable
private fun TorBanner(state: InvestTorBannerState) {
    ZappNotice(
        title = stringResource(R.string.invest_home_tor_banner_title),
        body = stringResource(R.string.invest_home_tor_banner_body),
    ) {
        ZappButton(
            text = stringResource(R.string.invest_home_tor_turn_on),
            variant = ZappButtonVariant.Primary,
            modifier = Modifier.weight(1f),
            onClick = state.onTurnOn,
        )
        ZappButton(
            text = stringResource(R.string.invest_home_tor_not_now),
            variant = ZappButtonVariant.Ghost,
            modifier = Modifier.weight(1f),
            onClick = state.onDismiss,
        )
    }
}

@Composable
private fun GroupHeader(group: InvestStockGroupState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = ZappTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md),
    ) {
        ZappSectionLabel(text = group.title.getValue())
        group.status?.let { ZappStatusChip(text = it.getValue(), variant = ZappChipVariant.Muted) }
    }
}

@Composable
private fun StockRow(row: InvestStockRowState) {
    InvestAssetRow(
        monogram = row.monogram,
        name = row.name,
        ticker = row.ticker,
        primaryValue = row.price?.getValue() ?: stringResource(R.string.invest_value_pending),
        secondaryValue = row.caption?.getValue(),
        isPrimaryMuted = !row.isPriced,
        horizontalPadding = 0.dp,
        onClick = row.onClick,
    )
}

@PreviewScreens
@Composable
private fun PreviewHome() {
    ZcashTheme {
        InvestHomeView(
            InvestHomeState(
                summary =
                    InvestHomeSummary(
                        total = stringRes("$198.94"),
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
                            ),
                        updatedAtEpochMillis = System.currentTimeMillis(),
                        isStale = false,
                        onRetry = {},
                    ),
                torBanner = InvestTorBannerState({}, {}),
                marketBanner = stringRes("US markets are closed. They reopen Mon 21:30 your time."),
                pendingTrades = listOf(InvestPendingTradeRow("t1abc", stringRes("Buy of NVIDIA in progress")) {}),
                recordsUnreadable = null,
                groups =
                    listOf(
                        InvestStockGroupState(
                            title = stringRes("Trades 24/7"),
                            status = null,
                            rows =
                                listOf(
                                    InvestStockRowState(
                                        "nvda",
                                        "NV",
                                        "NVIDIA",
                                        "NVDA",
                                        stringRes("$224.46"),
                                        stringRes("per share"),
                                        isPriced = true,
                                    ) {},
                                ),
                        ),
                        InvestStockGroupState(
                            title = stringRes("Weekdays"),
                            status = stringRes("Closed"),
                            rows =
                                listOf(
                                    InvestStockRowState(
                                        "aapl",
                                        "AP",
                                        "Apple",
                                        "AAPL",
                                        stringRes("$341.83"),
                                        stringRes("last price"),
                                        isPriced = false,
                                    ) {},
                                ),
                        ),
                    ),
                marketError = null,
                onRetryMarket = {},
                onBack = {},
            ),
        )
    }
}
