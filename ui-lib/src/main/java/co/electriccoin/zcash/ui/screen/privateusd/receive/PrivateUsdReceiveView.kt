// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.receive

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappAddressCopyRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.screen.receive.ReceiveBottomDock
import co.electriccoin.zcash.ui.screen.receive.ReceiveQrFrame
import co.electriccoin.zcash.ui.screen.receive.ReceiveShareButton
import co.electriccoin.zcash.ui.screen.receive.ReceiveTabSwitcher
import co.electriccoin.zcash.ui.screen.receive.ReceiveTip
import co.electriccoin.zcash.ui.design.R as DesignR

/** Laid out as receiving ZEC is: the code and address, the tabs below, Share, and back beside the one action. */
@Composable
internal fun PrivateUsdReceiveView(state: PrivateUsdReceiveState) {
    var showsInfo by rememberSaveable { mutableStateOf(false) }
    val usd = state.usd?.takeIf { state.tab == PrivateUsdReceiveTab.USD }
    val address = usd?.address ?: state.shielded.address
    val copyLabel = if (address.isCopied) R.string.private_usd_receive_copied else R.string.private_usd_receive_copy
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ZappTheme.colors.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ZappScreenHeader(
            title = stringResource(R.string.private_usd_receive_title),
            right = {
                ZappInfoButton(
                    contentDescription = stringResource(R.string.private_usd_info_content_description),
                    onClick = { showsInfo = true },
                )
            },
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            if (usd != null) Usd(usd) else Shielded(state.shielded)
            Spacer(Modifier.height(16.dp))
        }
        if (state.usd != null) {
            ReceiveTabSwitcher(
                labels =
                    listOf(
                        stringResource(R.string.private_usd_receive_tab_usd),
                        stringResource(R.string.private_usd_receive_tab_shielded),
                    ),
                selectedIndex = state.tab.ordinal,
                onSelect = { state.onSelectTab(PrivateUsdReceiveTab.entries[it]) },
            )
        }
        ReceiveShareButton(
            address = address.value,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 8.dp),
        )
        ReceiveBottomDock(
            onBack = state.onBack,
            action = stringResource(copyLabel),
            onAction = address.onCopy,
            isActionEnabled = address.value != null,
        )
    }
    if (showsInfo) {
        ZappInfoSheet(
            title = state.info.title.getValue(),
            steps = state.info.steps.map { it.getValue() },
            notes = state.info.notes.map { it.getValue() },
            onDismiss = { showsInfo = false },
            titleDescription = state.info.titleDescription?.getValue(),
        )
    }
}

@Composable
private fun Shielded(state: PrivateUsdReceiveShieldedState) {
    Address(state.address, centerImage = R.drawable.ic_private_usd_badge)
    ReceiveTip(label = stringResource(R.string.receive_tip_label), text = state.notice.getValue())
    Spacer(Modifier.height(12.dp))
    Caption(state.note)
}

@Composable
private fun Usd(state: PrivateUsdReceiveUsdState) {
    // Another token, or this one on another network, is lost: it's read before the address.
    ReceiveTip(
        label = stringResource(R.string.private_usd_receive_warning_label),
        text = state.notice.getValue(),
        isWarning = true,
    )
    Spacer(Modifier.height(20.dp))
    Address(state.address, centerImage = DesignR.drawable.ic_token_usdc)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xl),
    ) {
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
}

/** The code of the plain address over the address itself, shown whole: an abridged one is what lookalikes rely on. */
@Composable
private fun Address(
    address: PrivateUsdReceiveAddressState,
    @DrawableRes centerImage: Int,
) {
    val value = address.value
    if (value == null) {
        address.status?.let { Caption(it) }
    } else {
        ReceiveQrFrame(qrData = value, centerImage = centerImage, contentDescription = address.qrDescription)
        Spacer(Modifier.height(20.dp))
        ZappAddressCopyRow(
            address = styledStringResource(value),
            copyContentDescription = address.copyDescription.getValue(),
            onCopy = address.onCopy,
            label = address.label.getValue(),
        )
    }
    Spacer(Modifier.height(20.dp))
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

@Composable
private fun Caption(text: StringResource) {
    BasicText(
        text = text.getValue(),
        style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
    )
}
