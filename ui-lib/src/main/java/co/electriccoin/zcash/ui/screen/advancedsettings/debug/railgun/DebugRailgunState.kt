// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.railgun

data class DebugRailgunState(
    val status: List<String>,
    val address: String?,
    val onCopyAddress: () -> Unit,
    val balances: List<DebugRailgunBalance>,
    val gasAccount: List<String>,
    val onCopyGasAccount: () -> Unit,
    val activity: List<String>,
    val onCopyActivity: () -> Unit,
    val timings: List<String>,
    val error: String?,
    val isBusy: Boolean,
    val canAct: Boolean,
    val onRefresh: () -> Unit,
    val onShield: () -> Unit,
    val onSend: () -> Unit,
    val onWithdraw: () -> Unit,
    val onBack: () -> Unit,
)

data class DebugRailgunBalance(
    val bucket: String,
    val amounts: List<String>,
)
