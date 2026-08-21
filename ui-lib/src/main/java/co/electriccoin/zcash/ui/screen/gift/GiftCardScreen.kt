// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun GiftCardScreen() {
    val vm = koinViewModel<GiftCardVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    // enabled, not a bare BackHandler: funding and the ready screen must not be dismissible by the
    // system back gesture either, since neither has anywhere to go back to.
    BackHandler(enabled = true) { if (state.isBackEnabled) state.onBack() }
    GiftCardView(state = state)
}

@Serializable
data object GiftCardArgs
