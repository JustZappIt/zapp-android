// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.backgrounddelivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.push.PushRegistrar
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BackgroundDeliverySettingsVM(
    private val navigationRouter: NavigationRouter,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val pushRegistrar: PushRegistrar,
) : ViewModel() {
    private val isEnabled: StateFlow<Boolean> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED.observe(standardPreferenceProvider()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = false,
        )

    val state: StateFlow<BackgroundDeliverySettingsState> =
        isEnabled
            .map { enabled ->
                BackgroundDeliverySettingsState(
                    isEnabled = enabled,
                    onSaveClick = ::onSaveClick,
                    onBack = ::onBack,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    BackgroundDeliverySettingsState(
                        isEnabled = false,
                        onSaveClick = ::onSaveClick,
                        onBack = ::onBack,
                    ),
            )

    private fun onSaveClick(newValue: Boolean) {
        viewModelScope.launch {
            if (newValue) {
                StandardPreferenceKeys.IS_CHAT_NOTIFICATIONS_ENABLED.putValue(
                    preferenceProvider = standardPreferenceProvider(),
                    newValue = true,
                )
            }
            StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED.putValue(
                preferenceProvider = standardPreferenceProvider(),
                newValue = newValue,
            )
            pushRegistrar.sync()
            navigationRouter.back()
        }
    }

    private fun onBack() = navigationRouter.back()
}
