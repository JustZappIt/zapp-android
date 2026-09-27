// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PrivateUsdSendScreen(args: PrivateUsdSendArgs) {
    val vm = koinViewModel<PrivateUsdSendVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    PrivateUsdSendView(state = state)
    BackHandler { state.onBack() }
}

@Serializable
data class PrivateUsdSendArgs(
    val withdraw: Boolean
)
