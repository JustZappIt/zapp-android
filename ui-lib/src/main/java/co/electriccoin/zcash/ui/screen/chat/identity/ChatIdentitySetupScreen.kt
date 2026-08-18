// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.chat.identity

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.chat.view.ChatIdentitySetupView
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ChatIdentitySetupScreen() {
    val viewModel = koinViewModel<ChatIdentitySetupVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatIdentitySetupView(state = state, modifier = Modifier.fillMaxSize())
}
