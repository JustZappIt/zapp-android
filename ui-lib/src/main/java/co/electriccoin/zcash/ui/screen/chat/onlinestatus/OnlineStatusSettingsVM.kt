// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.onlinestatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnlineStatusSettingsVM(
    private val navigationRouter: NavigationRouter,
    private val standardPreferenceProvider: StandardPreferenceProvider,
) : ViewModel() {
    private val isEnabled: StateFlow<Boolean> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_SHOW_ONLINE_STATUS.observe(standardPreferenceProvider()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = true,
        )

    val state: StateFlow<OnlineStatusSettingsState> =
        isEnabled
            .map { enabled ->
                OnlineStatusSettingsState(
                    isEnabled = enabled,
                    onSaveClick = ::onSaveClick,
                    onBack = ::onBack,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    OnlineStatusSettingsState(
                        isEnabled = true,
                        onSaveClick = ::onSaveClick,
                        onBack = ::onBack,
                    ),
            )

    private fun onSaveClick(newValue: Boolean) {
        viewModelScope.launch {
            StandardPreferenceKeys.IS_CHAT_SHOW_ONLINE_STATUS.putValue(
                preferenceProvider = standardPreferenceProvider(),
                newValue = newValue,
            )
            navigationRouter.back()
        }
    }

    private fun onBack() = navigationRouter.back()
}
