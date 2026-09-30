// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun DebugAtomicSwapScreen() {
    val vm = koinViewModel<DebugAtomicSwapVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    DebugAtomicSwapView(state = state)
}

@Serializable
data object DebugAtomicSwapArgs
