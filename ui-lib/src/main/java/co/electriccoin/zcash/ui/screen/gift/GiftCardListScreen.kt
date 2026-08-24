// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun GiftCardListScreen() {
    val vm = koinViewModel<GiftCardListVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    state?.let { GiftCardListView(state = it) } ?: GiftCardListLoading()
}

@Serializable
data object GiftCardListArgs
