// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugAction
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugError
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugFrame
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugSection

@Composable
internal fun DebugAtomicSwapView(state: DebugAtomicSwapState) =
    DebugFrame(title = "Atomic swap (spike)", onBack = state.onBack) {
        DebugSection("Status", state.status)
        ZappButton(text = "Copy swap id", variant = ZappButtonVariant.Secondary, onClick = state.onCopySwapId)
        DebugAction("Quote and accept 1 unit (deposits right after)", !state.isBusy, state.onOpen)
        DebugAction("Advance now", !state.isBusy, state.onAdvance)
        DebugAction("Abandon (only if it never opened)", !state.isBusy, state.onAbandon)
        if (state.activity.isNotEmpty()) DebugSection("Activity", state.activity, mono = true)
        state.error?.let { DebugError(it) }
    }

@PreviewScreens
@Composable
private fun DebugAtomicSwapPreview() =
    ZcashTheme {
        DebugAtomicSwapView(
            state =
                DebugAtomicSwapState(
                    status = listOf("swap #0: 0x5c5a255afeaeacc7…", "quote: 202021 zat for 1000000 token base units"),
                    activity = listOf("open: swap #0 accepted for 202021 zat"),
                    error = null,
                    isBusy = false,
                    onOpen = {},
                    onAdvance = {},
                    onAbandon = {},
                    onCopySwapId = {},
                    onBack = {},
                ),
        )
    }
