// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun GroupInvitePreviewScreen(args: GroupInvitePreviewArgs) {
    val vm = koinViewModel<GroupInvitePreviewVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    GroupInvitePreviewView(state = state)
}

/**
 * Carries a `PendingGroupInviteStore` token, never the link: a typed route is serialised into the
 * back stack and into saved instance state, and the link is a bearer secret.
 *
 * A null [token] opens the screen for a link the store refused, so the tap lands somewhere that
 * says why. [comingSoon] opens it for any link while the feature is off.
 */
@Serializable
data class GroupInvitePreviewArgs(
    val token: String? = null,
    val comingSoon: Boolean = false,
)
