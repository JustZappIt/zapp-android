// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.refunds

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun PrivateUsdRefundsScreen() {
    val vm = koinViewModel<PrivateUsdRefundsVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    PrivateUsdRefundsView(state)
    BackHandler { state.onBack() }
}

@Serializable
data object PrivateUsdRefundsArgs
