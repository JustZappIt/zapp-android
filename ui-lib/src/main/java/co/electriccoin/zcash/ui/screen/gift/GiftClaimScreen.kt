// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun GiftClaimScreen(args: GiftClaimArgs) {
    val vm = koinViewModel<GiftClaimVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = true) { if (state.isBackEnabled) state.onBack() }
    GiftClaimView(state = state)
}

/**
 * Carries a `PendingGiftLinkStore` token, never the link.
 *
 * The link's fragment is the bearer secret, and a typed route argument is serialised into the back
 * stack entry and into saved instance state, so the link itself stays in memory and only this token
 * travels (§3.7). It is meaningless once taken.
 */
@Serializable
data class GiftClaimArgs(
    val token: String,
)
