// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.railgun

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme

@Composable
internal fun DebugRailgunView(state: DebugRailgunState) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = PADDING.dp, end = PADDING.dp, bottom = BACK_DOCK_CLEARANCE.dp),
            verticalArrangement = Arrangement.spacedBy(GAP.dp),
        ) {
            ZappScreenHeader(title = "Railgun wallet (spike)")
            Section("Status", state.status)
            state.address?.let { address ->
                Section("0zk address", listOf(address), mono = true)
                SmallButton("Copy address", state.onCopyAddress)
            }
            if (state.balances.isEmpty()) {
                Section("Balances", listOf(if (state.address == null) "not synced yet" else "none"))
            } else {
                state.balances.forEach { (bucket, tokens) -> Section("Balance: $bucket", tokens) }
            }
            if (state.gasAccount.isNotEmpty()) {
                Section("Gas account (sends in place of a broadcaster)", state.gasAccount, mono = true)
                SmallButton("Copy gas account", state.onCopyGasAccount)
            }
            ActionButton("Shield 0.01 ETH", state.canAct, state.onShield)
            ActionButton("Send 0.001 WETH privately to itself", state.canAct, state.onSend)
            ActionButton("Withdraw 0.001 WETH to the gas account", state.canAct, state.onWithdraw)
            if (state.activity.isNotEmpty()) {
                Section("Activity", state.activity, mono = true)
                SmallButton("Copy activity with links", state.onCopyActivity)
            }
            if (state.timings.isNotEmpty()) Section("Timings", state.timings)
            state.error?.let { error ->
                BasicText(text = error, style = ZappTheme.typography.body.copy(color = c.danger))
            }
            ZappButton(
                text = "Sync",
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canAct,
                loading = state.isBusy,
                onClick = state.onRefresh,
            )
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = PADDING.dp, bottom = GAP.dp)
                    .background(c.surface, RectangleShape)
                    .border(BorderStroke(1.dp, c.accent), RectangleShape),
        ) {
            ZappBackButton(onClick = state.onBack)
        }
    }
}

@Composable
private fun Section(
    title: String,
    lines: List<String>,
    mono: Boolean = false,
) {
    val c = ZappTheme.colors
    ZappBorderedCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LINE_GAP.dp)) {
        BasicText(text = title, style = ZappTheme.typography.eyebrow.copy(color = c.textMuted))
        val style = if (mono) ZappTheme.typography.mono else ZappTheme.typography.body
        lines.forEach { BasicText(text = it, style = style.copy(color = c.text)) }
    }
}

@Composable
private fun SmallButton(
    text: String,
    onClick: () -> Unit,
) = ZappButton(text = text, variant = ZappButtonVariant.Secondary, onClick = onClick)

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) = ZappButton(
    text = text,
    modifier = Modifier.fillMaxWidth(),
    variant = ZappButtonVariant.Secondary,
    enabled = enabled,
    onClick = onClick
)

private const val PADDING = 20
private const val GAP = 12
private const val LINE_GAP = 4
private const val BACK_DOCK_CLEARANCE = 96

@PreviewScreens
@Composable
private fun DebugRailgunPreview() =
    ZcashTheme {
        DebugRailgunView(
            state =
                DebugRailgunState(
                    status = listOf("network: sepolia", "phase: ready", "notes: complete"),
                    address = "0zk1qynadd6na2he…",
                    onCopyAddress = {},
                    balances = listOf("spendable" to listOf("0.01 WETH"), "shield_pending" to listOf("0.02 WETH")),
                    gasAccount = listOf("0x90C670d5752546412B56B5372D2B720b6bBeFae6", "0.05 ETH"),
                    onCopyGasAccount = {},
                    activity = listOf("shield: 0x8f3a2c1b9d4e…", "send_to_self, proof 23.4s: 0x1b2c3d4e5f6a…"),
                    onCopyActivity = {},
                    timings = listOf("start: 2.6s", "open: 3.6s", "sync: 31.8s"),
                    error = null,
                    isBusy = false,
                    canAct = true,
                    onRefresh = {},
                    onShield = {},
                    onSend = {},
                    onWithdraw = {},
                    onBack = {},
                ),
        )
    }
