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
internal fun GroupLinkScreen(args: GroupLinkArgs) {
    val vm = koinViewModel<GroupLinkVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    GroupLinkView(state = state)
}

/** Carries the group, never the link: a typed route is kept in the back stack and in saved state. */
@Serializable
data class GroupLinkArgs(
    val conversationId: String,
)
