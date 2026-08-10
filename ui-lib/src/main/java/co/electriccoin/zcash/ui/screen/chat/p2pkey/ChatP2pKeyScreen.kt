// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.p2pkey

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatP2pKeyScreen() {
    val vm = koinViewModel<ChatP2pKeyVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    ChatP2pKeyView(state = state)
}

@Serializable
object ChatP2pKeyArgs
