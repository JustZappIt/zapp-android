// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.walletaddress

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatWalletAddressScreen() {
    val vm = koinViewModel<ChatWalletAddressVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    ChatWalletAddressView(state = state)
}

@Serializable
object ChatWalletAddressArgs
