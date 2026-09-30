// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappRefreshButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun PrivateUsdView(state: PrivateUsdState) {
    PrivateUsdLazyScaffold(
        title = stringResource(R.string.private_usd_title),
        subtitle = stringResource(R.string.private_usd_subtitle),
        info = state.info,
        onBack = state.onBack,
        primaryButton = state.convertButton,
    ) {
        item { Overview(state) }
        activity(state.activity)
    }
}

@Composable
private fun Overview(state: PrivateUsdState) {
    Column(
        modifier = Modifier.padding(horizontal = ZappTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xl2),
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
}

@Composable
private fun Total(state: PrivateUsdState) {
    val c = ZappTheme.colors
    var usdFirst by rememberSaveable { mutableStateOf(false) }
    val primary = if (usdFirst && state.usdHeadline != null) state.usdHeadline else state.headline
    val secondary = if (usdFirst && state.usdHeadline != null) state.headline else state.usdHeadline
    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth().then(
                    if (state.usdHeadline != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.private_usd_switch_currency),
                        ) { usdFirst = !usdFirst }
                    } else {
                        Modifier
                    }
                ),
            horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md),
        ) {
            BasicText(
                text = primary.getValue(),
                style =
                    ZappTheme.typography.balanceDisplay.copy(
                        color = if (state.isHeadlineKnown) c.text else c.textSubtle,
                    ),
                modifier = Modifier.weight(1f).alignByBaseline(),
                maxLines = 1,
                autoSize =
                    TextAutoSize.StepBased(
                        minFontSize = ZappTheme.typography.displaySecondary.fontSize,
                        maxFontSize = ZappTheme.typography.balanceDisplay.fontSize,
                    ),
            )
            secondary?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    modifier = Modifier.alignByBaseline(),
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = state.status?.getValue().orEmpty(),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                modifier = Modifier.weight(1f),
            )
            ZappRefreshButton(
                isRefreshing = state.isRefreshing,
                contentDescription = stringResource(R.string.private_usd_refresh),
                refreshingDescription = stringResource(R.string.private_usd_updating),
                onClick = state.onRefresh,
            )
        }
        state.refreshError?.let {
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.danger),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
        ZappSectionLabel(
            text = stringResource(R.string.private_usd_assets_title),
            modifier = Modifier.semantics { heading() },
        )
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
            assets.forEach { ZappSummaryRow(label = it.name.getValue(), value = it.amount.getValue()) }
        }
    }
}

private fun LazyListScope.activity(activity: List<PrivateUsdActivityState>) {
    item {
        ZappSectionLabel(
            text = stringResource(R.string.private_usd_activity_title),
            modifier =
                Modifier
                    .padding(
                        start = ZappTheme.spacing.gutter,
                        end = ZappTheme.spacing.gutter,
                        top = ZappTheme.spacing.xl2,
                        bottom = ZappTheme.spacing.md,
                    ).semantics { heading() },
        )
    }
    if (activity.isEmpty()) {
        item {
            BasicText(
                text = stringResource(R.string.private_usd_activity_empty),
                style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
                modifier = Modifier.padding(horizontal = ZappTheme.spacing.gutter),
            )
        }
    } else {
        val lastKey = activity.last().key
        items(items = activity, key = { it.key }) { row ->
            ActivityRow(row)
            if (row.key != lastKey) ZappRowDivider()
        }
    }
}

@Composable
private fun ActivityRow(row: PrivateUsdActivityState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    row.onClick?.let {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = row.onClickLabel?.getValue(),
                            onClick = it,
                        )
                    } ?: Modifier.semantics(mergeDescendants = true) {}
                ).padding(horizontal = ZappTheme.spacing.gutter, vertical = ZappTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xxs)) {
            BasicText(text = row.title.getValue(), style = ZappTheme.typography.rowTitle.copy(color = c.text))
            BasicText(
                text = row.detail.getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                maxLines = 1,
            )
        }
        Column(
            modifier = Modifier.padding(start = ZappTheme.spacing.lg),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xxs),
        ) {
            row.amount?.let {
                BasicText(
                    text = it.getValue(),
                    style =
                        ZappTheme.typography.rowTitle.copy(
                            color =
                                when (row.tone) {
                                    PrivateUsdActivityTone.IN -> c.success
                                    PrivateUsdActivityTone.OUT -> c.text
                                    PrivateUsdActivityTone.NEUTRAL -> c.textMuted
                                },
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }
            row.local?.let {
                BasicText(text = it.getValue(), style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted))
            }
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
            modifier = Modifier.semantics { heading() },
        )
        BasicText(
            text = stringResource(R.string.private_usd_empty_body),
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
    }
}

@Composable
private fun Sending(sending: PrivateUsdSendingState) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.lg)) {
        ZappButton(
            text = stringResource(R.string.private_usd_action_send),
            leadingIcon = Icons.AutoMirrored.Filled.Send,
            variant = ZappButtonVariant.Secondary,
            enabled = sending.isEnabled,
            modifier = Modifier.weight(1f),
            onClick = sending.onSend,
        )
        ZappButton(
            text = stringResource(R.string.private_usd_action_withdraw),
            leadingIcon = Icons.AutoMirrored.Filled.CallMade,
            variant = ZappButtonVariant.Secondary,
            enabled = sending.isEnabled,
            modifier = Modifier.weight(1f),
            onClick = sending.onWithdraw,
        )
    }
}

@PreviewScreens
@Composable
private fun PrivateUsdPreview() =
    ZcashTheme {
        PrivateUsdView(
            state =
                PrivateUsdState(
                    headline = stringRes("₹1,112.40"),
                    isHeadlineKnown = true,
                    usdHeadline = stringRes("$13.32"),
                    rows =
                        listOf(
                            PrivateUsdRowState(stringRes("Available"), stringRes("₹1,030.56"), null),
                            PrivateUsdRowState(
                                stringRes("Arriving"),
                                stringRes("₹81.84"),
                                stringRes("Railgun screens new funds before they can be spent."),
                            ),
                        ),
                    assets = emptyList(),
                    status = stringRes("Updated Sep 29 2:32 PM"),
                    isRefreshing = false,
                    refreshError = null,
                    isEmpty = false,
                    conversion = null,
                    sending = PrivateUsdSendingState(isEnabled = true, onSend = {}, onWithdraw = {}),
                    activity =
                        listOf(
                            PrivateUsdActivityState(
                                key = "sent",
                                title = stringRes("Withdrew"),
                                detail = stringRes("Sep 26 5:31 PM · to 0x1c7f9a…5539"),
                                amount = stringRes("−$1.00"),
                                local = stringRes("₹83.51"),
                                tone = PrivateUsdActivityTone.OUT,
                                onClick = {},
                            ),
                            PrivateUsdActivityState(
                                key = "to-usd",
                                title = stringRes("Converted ZEC"),
                                detail = stringRes("Sep 26 5:12 PM · 0.00202021 ZEC"),
                                amount = stringRes("+$0.98"),
                                local = stringRes("₹81.84"),
                                tone = PrivateUsdActivityTone.IN,
                                onClick = {},
                            ),
                        ),
                    info = PrivateUsdInfo(title = stringRes("About private USD")),
                    convertButton = ButtonState(stringRes("Convert")),
                    onRefresh = {},
                    onBack = {},
                ),
        )
    }
