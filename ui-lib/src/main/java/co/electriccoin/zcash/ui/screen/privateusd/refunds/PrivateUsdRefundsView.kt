// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.refunds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyOverlay
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappRefreshButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdLazyScaffold

@Composable
internal fun PrivateUsdRefundsView(state: PrivateUsdRefundsState) {
    PrivateUsdLazyScaffold(
        title = stringResource(R.string.refunds_title),
        subtitle = stringResource(R.string.refunds_subtitle),
        info =
            PrivateUsdInfo(
                title = stringRes(R.string.refunds_title),
                notes = listOf(stringRes(R.string.refunds_info)),
            ),
        onBack = state.onBack,
        isBackEnabled = state.isBackEnabled,
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZappTheme.spacing.gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stringResource(R.string.refunds_info),
                    style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
                    modifier = Modifier.weight(1f),
                )
                ZappRefreshButton(
                    isRefreshing = state.isLoading || state.isRefreshing,
                    contentDescription = stringResource(R.string.private_usd_refresh),
                    refreshingDescription = stringResource(R.string.refunds_checking),
                    onClick = state.onRefresh,
                )
            }
        }
        state.error?.let { error ->
            item {
                BasicText(
                    text = error.getValue(),
                    style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.danger),
                    modifier =
                        Modifier
                            .padding(ZappTheme.spacing.gutter)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (state.refunds.isEmpty() && state.error == null) {
            item {
                BasicText(
                    text = stringResource(if (state.isLoading) R.string.refunds_checking else R.string.refunds_empty),
                    style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
                    modifier = Modifier.padding(ZappTheme.spacing.gutter),
                )
            }
        }
        items(state.refunds, key = { it.index }) { refund ->
            Refund(refund)
        }
    }
    state.pinVerify?.let { PinVerifyOverlay(it) }
}

@Composable
private fun Refund(refund: PrivateUsdRefundState) {
    Column(modifier = Modifier.padding(horizontal = ZappTheme.spacing.gutter, vertical = ZappTheme.spacing.md)) {
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
            BasicText(
                text = refund.date.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = ZappTheme.colors.text),
            )
            refund.amount?.let { amount ->
                ZappSummaryRow(label = stringResource(R.string.refunds_original_amount), value = amount.getValue())
            }
            BasicText(
                text = refund.status.getValue(),
                style =
                    ZappTheme.typography.body.copy(
                        color = if (refund.isProblem) ZappTheme.colors.danger else ZappTheme.colors.textMuted
                    ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            refund.error?.let { error ->
                BasicText(
                    text = error.getValue(),
                    style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.danger),
                )
            }
            refund.recover?.let { button ->
                ZappButton(
                    text = button.text.getValue(),
                    enabled = button.isEnabled,
                    loading = button.isLoading,
                    variant = ZappButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = button.onClick,
                )
            }
        }
    }
}
