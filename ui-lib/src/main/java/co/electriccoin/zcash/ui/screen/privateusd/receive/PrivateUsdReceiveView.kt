// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.receive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.QrCodeDefaults
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyableAddress
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegment
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegmentedSelector
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold

@Composable
internal fun PrivateUsdReceiveView(state: PrivateUsdReceiveState) {
    PrivateUsdScaffold(
        title = stringResource(R.string.private_usd_receive_title),
        info = state.info,
        onBack = state.onBack,
    ) {
        val usd = state.usd
        if (usd != null) {
            ZappSegmentedSelector(
                segments =
                    listOf(
                        ZappSegment(stringResource(R.string.private_usd_receive_tab_usd)),
                        ZappSegment(stringResource(R.string.private_usd_receive_tab_shielded)),
                    ),
                selectedIndex = state.tab.ordinal,
                onSelect = { state.onSelectTab(PrivateUsdReceiveTab.entries[it]) },
            )
        }
        if (usd != null && state.tab == PrivateUsdReceiveTab.USD) {
            Usd(usd)
        } else {
            Shielded(state.shielded)
        }
    }
}

@Composable
private fun Shielded(state: PrivateUsdReceiveShieldedState) {
    Notice(state.notice, isDanger = false)
    Address(state.address)
    Caption(state.note)
}

@Composable
private fun Usd(state: PrivateUsdReceiveUsdState) {
    // Another token, or this one on another network, is lost: it's read before the address.
    Notice(state.notice, isDanger = true)
    Address(state.address)
    if (state.lines.isNotEmpty()) {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm),
        ) {
            state.lines.forEach { Line(it) }
        }
    }
    state.fees?.let { Fees(it) }
    state.feesStatus?.let { Caption(it) }
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        state.notes.forEach { Caption(it) }
    }
}

/** A code of the plain address over the address itself, shown whole: an abridged one is what lookalikes rely on. */
@Composable
private fun Address(address: PrivateUsdReceiveAddressState) {
    val value = address.value
    if (value == null) {
        address.status?.let { Caption(it) }
        return
    }
    val c = ZappTheme.colors
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .background(c.bg, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(ZappTheme.spacing.lg),
        ) {
            ZashiQr(
                state = QrState(qrData = value, contentDescription = address.qrDescription),
                colors = QrCodeDefaults.colors(border = Color.Unspecified),
            )
        }
    }
    ZappCopyableAddress(
        label = address.label.getValue(),
        address = value,
        copyContentDescription = address.copyDescription.getValue(),
        onCopy = address.onCopy,
        isCopied = address.isCopied,
        maxLines = Int.MAX_VALUE,
    )
}

@Composable
private fun Fees(fees: PrivateUsdReceiveFeesState) {
    ZappSettlementLedger(
        rows =
            listOf(
                ZappSettlementLedgerRow(
                    stringResource(R.string.private_usd_receive_usd_minimum),
                    fees.minimum.getValue(),
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.private_usd_receive_usd_relayer_fee),
                    fees.relayerFee.getValue(),
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.private_usd_receive_usd_railgun_fee),
                    fees.railgunFee.getValue(),
                ),
            ),
        notice = fees.example?.getValue(),
    )
}

@Composable
private fun Line(line: PrivateUsdReceiveLineState) {
    val c = ZappTheme.colors
    BasicText(
        text = line.text.getValue(),
        style =
            ZappTheme.typography.body.copy(
                color =
                    when (line.tone) {
                        PrivateUsdReceiveLineTone.ACTIVE -> c.text
                        PrivateUsdReceiveLineTone.MUTED -> c.textMuted
                        PrivateUsdReceiveLineTone.DONE -> c.success
                    },
            ),
    )
}

/** What must be read before sending, set off by a bar down its side. */
@Composable
private fun Notice(
    text: StringResource,
    isDanger: Boolean,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(if (isDanger) c.dangerSoft else c.accentSoft, RectangleShape),
    ) {
        Box(
            modifier =
                Modifier
                    .width(NOTICE_BAR_WIDTH)
                    .fillMaxHeight()
                    .background(if (isDanger) c.danger else c.accent, RectangleShape),
        )
        BasicText(
            text = text.getValue(),
            style =
                ZappTheme.typography.caption.copy(
                    color = if (isDanger) c.danger else c.accentText,
                    fontWeight = FontWeight.Medium,
                ),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = ZappTheme.spacing.lg, vertical = ZappTheme.spacing.md),
        )
    }
}

@Composable
private fun Caption(text: StringResource) {
    BasicText(
        text = text.getValue(),
        style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
    )
}

private val NOTICE_BAR_WIDTH = 3.dp
