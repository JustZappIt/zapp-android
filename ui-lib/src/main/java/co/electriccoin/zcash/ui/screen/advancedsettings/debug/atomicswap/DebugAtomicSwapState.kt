// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

data class DebugAtomicSwapState(
    val status: List<String>,
    val depositAddress: String?,
    val onCopyAddress: () -> Unit,
    val activity: List<String>,
    val error: String?,
    val isBusy: Boolean,
    val onPrepare: () -> Unit,
    val onImport: () -> Unit,
    val onSweep: () -> Unit,
    val onDelete: () -> Unit,
    val onBack: () -> Unit,
)
