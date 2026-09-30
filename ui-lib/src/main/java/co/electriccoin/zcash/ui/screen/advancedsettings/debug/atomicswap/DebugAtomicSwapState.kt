// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

data class DebugAtomicSwapState(
    val status: List<String>,
    val activity: List<String>,
    val error: String?,
    val isBusy: Boolean,
    val onOpen: () -> Unit,
    val onAdvance: () -> Unit,
    val onAbandon: () -> Unit,
    val onCopySwapId: () -> Unit,
    val onBack: () -> Unit,
)
