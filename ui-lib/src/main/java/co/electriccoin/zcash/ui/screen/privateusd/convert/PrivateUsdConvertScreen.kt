// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseVM
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseView
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun PrivateUsdConvertScreen(
    initialDirection: PrivateUsdConversionDirection = PrivateUsdConversionDirection.ZEC_TO_USD
) {
    var direction by rememberSaveable { mutableStateOf(initialDirection) }
    val switchDirection = { direction = direction.opposite }
    when (direction) {
        PrivateUsdConversionDirection.ZEC_TO_USD -> ZecToUsd(onSwitchDirection = switchDirection)
        PrivateUsdConversionDirection.USD_TO_ZEC -> UsdToZec(onSwitchDirection = switchDirection)
    }
}

@Composable
private fun ZecToUsd(onSwitchDirection: () -> Unit) {
    val vm = koinViewModel<PrivateUsdConvertVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    PrivateUsdConvertView(
        state = state,
        onSwitchDirection = {
            vm.resetAmount()
            onSwitchDirection()
        },
    )
    BackHandler { state.onBack() }
}

@Composable
private fun UsdToZec(onSwitchDirection: () -> Unit) {
    val vm = koinViewModel<PrivateUsdReverseVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    PrivateUsdReverseView(
        state = state,
        onSwitchDirection = {
            vm.resetAmount()
            onSwitchDirection()
        },
    )
    BackHandler { state.onBack() }
}

@Serializable
data object PrivateUsdConvertArgs
