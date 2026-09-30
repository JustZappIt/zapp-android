// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.railgun

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugAction
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugError
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugFrame
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugSection

@Composable
internal fun DebugRailgunView(state: DebugRailgunState) =
    DebugFrame(title = "Railgun wallet (spike)", onBack = state.onBack) {
        DebugSection("Status", state.status)
        state.address?.let { address ->
            DebugSection("0zk address", listOf(address), mono = true)
            SmallButton("Copy address", state.onCopyAddress)
        }
        if (state.balances.isEmpty()) {
            DebugSection("Balances", listOf(if (state.address == null) "not synced yet" else "none"))
        } else {
            state.balances.forEach { DebugSection("Balance: ${it.bucket}", it.amounts) }
        }
        if (state.gasAccount.isNotEmpty()) {
            DebugSection("Gas account (sends in place of a broadcaster)", state.gasAccount, mono = true)
            SmallButton("Copy gas account", state.onCopyGasAccount)
        }
        DebugAction("Shield 0.01 ETH", state.canAct, state.onShield)
        DebugAction("Send 0.001 WETH privately to itself", state.canAct, state.onSend)
        DebugAction("Withdraw 0.001 WETH to the gas account", state.canAct, state.onWithdraw)
        if (state.activity.isNotEmpty()) {
            DebugSection("Activity", state.activity, mono = true)
            SmallButton("Copy activity with links", state.onCopyActivity)
        }
        if (state.timings.isNotEmpty()) DebugSection("Timings", state.timings)
        state.error?.let { DebugError(it) }
        ZappButton(
            text = "Sync",
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canAct,
            loading = state.isBusy,
            onClick = state.onRefresh,
        )
    }

@Composable
private fun SmallButton(
    text: String,
    onClick: () -> Unit,
) = ZappButton(text = text, variant = ZappButtonVariant.Secondary, onClick = onClick)

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
                    balances =
                        listOf(
                            DebugRailgunBalance("spendable", listOf("0.01 WETH")),
                            DebugRailgunBalance("shield_pending", listOf("0.02 WETH")),
                        ),
                    gasAccount = listOf("0x90C670d5752546412B56B5372D2B720b6bBeFae6", "0.05 ETH"),
                    onCopyGasAccount = {},
                    activity = listOf("shield, confirmed: 0x8f3a…", "send_to_self, proof 23.4s, unconfirmed: 0x1b2c…"),
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
