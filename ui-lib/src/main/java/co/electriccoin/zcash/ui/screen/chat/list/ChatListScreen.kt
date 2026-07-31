// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.chat.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.chat.view.ChatListView
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ChatListScreen(showBackButton: Boolean = true) {
    val viewModel = koinViewModel<ChatListVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenVisible()
        // Nothing on pause: clearing here would race a room that has already claimed the slot.
        onPauseOrDispose {}
    }
    BackHandler { state.onBack() }
    ChatListView(
        state = state,
        showBackButton = showBackButton,
        modifier = Modifier.fillMaxSize(),
    )
}
