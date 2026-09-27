// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.widget.PrivateUsdConversionBanner

@Composable
internal fun PrivateUsdView(state: PrivateUsdState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(
            title = stringResource(R.string.private_usd_title),
            subtitle = stringResource(R.string.private_usd_subtitle),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ZappTheme.spacing.xl2, vertical = ZappTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xl),
        ) {
            Total(state)
            state.conversion?.let { PrivateUsdConversionBanner(state = it) }
            if (state.isEmpty) {
                Empty()
            } else if (state.rows.isNotEmpty()) {
                Buckets(state.rows)
            }
            if (state.assets.isNotEmpty()) Assets(state.assets)
            Sending(state.sending)
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                ZappButton(
                    text = stringResource(R.string.private_usd_action_convert),
                    modifier = Modifier.weight(1f).padding(start = ZappTheme.spacing.lg),
                    onClick = state.onConvert,
                )
            },
        )
    }
}

@Composable
private fun Total(state: PrivateUsdState) {
    val c = ZappTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
        BasicText(
            text = state.total?.getValue() ?: "—",
            style = ZappTheme.typography.balanceDisplay.copy(color = if (state.total == null) c.textSubtle else c.text),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = state.status?.getValue().orEmpty(),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                modifier = Modifier.weight(1f),
            )
            if (!state.isRefreshing) {
                ZappCompactButton(text = stringResource(R.string.private_usd_refresh), onClick = state.onRefresh)
            }
        }
        if (state.refreshFailed) {
            BasicText(
                text = stringResource(R.string.private_usd_refresh_failed),
                style = ZappTheme.typography.caption.copy(color = c.danger),
            )
        }
    }
}

@Composable
private fun Buckets(rows: List<PrivateUsdRowState>) {
    val c = ZappTheme.colors
    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.lg)) {
        rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
                ZappSummaryRow(
                    label = row.label.getValue(),
                    value = row.amount.getValue(),
                    valueColor = if (row.isDanger) c.danger else c.text,
                )
                row.explanation?.let {
                    BasicText(
                        text = it.getValue(),
                        style = ZappTheme.typography.caption.copy(color = if (row.isDanger) c.danger else c.textMuted),
                    )
                }
            }
        }
    }
}

@Composable
private fun Assets(assets: List<PrivateUsdAssetState>) {
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(R.string.private_usd_assets_title))
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
            assets.forEach { ZappSummaryRow(label = it.name, value = it.amount.getValue()) }
        }
    }
}

@Composable
private fun Empty() {
    val c = ZappTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm)) {
        BasicText(
            text = stringResource(R.string.private_usd_empty_title),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
        BasicText(
            text = stringResource(R.string.private_usd_empty_body),
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
    }
}

@Composable
private fun Sending(sending: PrivateUsdSendingState?) {
    if (sending == null) {
        BasicText(
            text = stringResource(R.string.private_usd_send_later),
            style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.lg)) {
            ZappButton(
                text = stringResource(R.string.private_usd_action_send),
                variant = ZappButtonVariant.Secondary,
                enabled = sending.isEnabled,
                modifier = Modifier.weight(1f),
                onClick = sending.onSend,
            )
            ZappButton(
                text = stringResource(R.string.private_usd_action_withdraw),
                variant = ZappButtonVariant.Secondary,
                enabled = sending.isEnabled,
                modifier = Modifier.weight(1f),
                onClick = sending.onWithdraw,
            )
        }
    }
}

@PreviewScreens
@Composable
private fun PrivateUsdPreview() =
    ZcashTheme {
        PrivateUsdView(
            state =
                PrivateUsdState(
                    total = stringRes("$13.32"),
                    rows =
                        listOf(
                            PrivateUsdRowState(stringRes("Available"), stringRes("$12.34"), null),
                            PrivateUsdRowState(
                                stringRes("Arriving"),
                                stringRes("$0.98"),
                                stringRes("Railgun screens new funds before they can be spent."),
                            ),
                        ),
                    assets = emptyList(),
                    status = stringRes("Updated 14:32"),
                    isRefreshing = false,
                    refreshFailed = false,
                    isEmpty = false,
                    conversion = null,
                    sending = PrivateUsdSendingState(isEnabled = true, onSend = {}, onWithdraw = {}),
                    onConvert = {},
                    onRefresh = {},
                    onBack = {},
                ),
        )
    }
