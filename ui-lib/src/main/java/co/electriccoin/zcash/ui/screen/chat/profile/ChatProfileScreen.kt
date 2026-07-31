// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.chat.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.chat.view.ChatProfileView
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ChatProfileScreen() {
    val viewModel = koinViewModel<ChatProfileVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    ChatProfileView(state = state, modifier = Modifier.fillMaxSize())
}
