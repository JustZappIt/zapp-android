// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import xyz.justzappit.offramp.p2p.CurrencyCode

@Composable
fun IncreaseReputationScreen(args: IncreaseReputationArgs) {
    val vm = koinViewModel<IncreaseReputationVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    IncreaseReputationView(state)
    BackHandler { state.onBack() }
}

/** The corridor decides what a verification is worth, so it travels with the route. */
@Serializable
data class IncreaseReputationArgs(
    val currency: CurrencyCode,
    val reclaimSessionId: String? = null,
    val reclaimPlatform: String? = null,
)
