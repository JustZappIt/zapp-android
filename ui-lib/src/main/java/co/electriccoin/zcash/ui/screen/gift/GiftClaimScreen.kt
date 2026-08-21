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
 * Carries the whole gift URI, fragment included.
 *
 * The fragment is the bearer secret, so this route argument is money. It is never logged and never
 * put in a notification; `MainActivity` consumes the intent that produced it exactly once so a
 * recreation or a Recents re-entry cannot re-enqueue the same claim (§3.7).
 */
@Serializable
data class GiftClaimArgs(
    val uri: String,
) {
    override fun toString(): String = "GiftClaimArgs(redacted)"
}
