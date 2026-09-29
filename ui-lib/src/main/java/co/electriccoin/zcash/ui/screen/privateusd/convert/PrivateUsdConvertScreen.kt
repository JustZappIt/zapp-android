// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseVM
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseView
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun PrivateUsdConvertScreen(initialReverse: Boolean = false) {
    val reverseRepository = koinInject<ReverseSwapRepository>()
    val reverse by reverseRepository.state.collectAsStateWithLifecycle()
    var direction by rememberSaveable {
        mutableStateOf(
            if (initialReverse) PrivateUsdConversionDirection.USD_TO_ZEC else PrivateUsdConversionDirection.ZEC_TO_USD
        )
    }
    LaunchedEffect(reverse.record?.index) {
        if (reverse.record?.underWay == true) direction = PrivateUsdConversionDirection.USD_TO_ZEC
    }
    val forwardVM = koinViewModel<PrivateUsdConvertVM>()
    val reverseVM = koinViewModel<PrivateUsdReverseVM>()

    fun flip(next: PrivateUsdConversionDirection) {
        forwardVM.resetAmount()
        reverseVM.resetAmount()
        direction = next
    }
    when (direction) {
        PrivateUsdConversionDirection.ZEC_TO_USD -> {
            val vm = forwardVM
            val state by vm.state.collectAsStateWithLifecycle()
            PrivateUsdConvertView(state = state, directionSelector = {
                PrivateUsdConversionDirection(direction) {
                    if (state.isQuoting ||
                        !state.primaryButton.isLoading
                    ) {
                        flip(it)
                    }
                }
            })
            BackHandler { state.onBack() }
        }

        PrivateUsdConversionDirection.USD_TO_ZEC -> {
            val vm = reverseVM
            val state by vm.state.collectAsStateWithLifecycle()
            PrivateUsdReverseView(state = state, directionSelector = {
                PrivateUsdConversionDirection(direction) {
                    if ((state.isQuoting || !state.primary.isLoading) &&
                        reverse.record?.underWay != true
                    ) {
                        flip(it)
                    }
                }
            })
            BackHandler { state.onBack() }
        }
    }
}

@Serializable
data object PrivateUsdConvertArgs
