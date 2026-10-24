// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.receive

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun PrivateUsdReceiveScreen() {
    val vm = koinViewModel<PrivateUsdReceiveVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    PrivateUsdReceiveView(state = state)
    BackHandler { state.onBack() }
}

/** The Shielded USD page's Receive: the only way to the wallet's addresses. */
@Serializable
data object PrivateUsdReceiveArgs
