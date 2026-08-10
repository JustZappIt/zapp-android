// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.BooleanPreferenceDefault
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.push.PushRegistrar
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatSettingsVM(
    private val navigationRouter: NavigationRouter,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val pushRegistrar: PushRegistrar,
) : ViewModel() {
    val state: StateFlow<ChatSettingsState> =
        combine(
            observe(StandardPreferenceKeys.IS_CHAT_READ_RECEIPTS_ENABLED),
            observe(StandardPreferenceKeys.IS_CHAT_SHOW_ONLINE_STATUS),
            observe(StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED),
        ) { readReceipts, onlineStatus, backgroundDelivery ->
            createState(
                ChatPreferences(
                    isReadReceiptsEnabled = readReceipts,
                    isOnlineStatusEnabled = onlineStatus,
                    isBackgroundDeliveryEnabled = backgroundDelivery,
                )
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(DEFAULT_PREFERENCES),
        )

    private fun observe(preference: BooleanPreferenceDefault): Flow<Boolean> =
        flow { emitAll(preference.observe(standardPreferenceProvider())) }

    private fun createState(preferences: ChatPreferences) =
        ChatSettingsState(
            preferences = preferences,
            onSaveClick = ::onSaveClick,
            onBack = ::onBack,
        )

    private fun onSaveClick(preferences: ChatPreferences) {
        viewModelScope.launch {
            val provider = standardPreferenceProvider()
            StandardPreferenceKeys.IS_CHAT_READ_RECEIPTS_ENABLED.putValue(
                preferenceProvider = provider,
                newValue = preferences.isReadReceiptsEnabled,
            )
            StandardPreferenceKeys.IS_CHAT_SHOW_ONLINE_STATUS.putValue(
                preferenceProvider = provider,
                newValue = preferences.isOnlineStatusEnabled,
            )
            if (preferences.isBackgroundDeliveryEnabled) {
                StandardPreferenceKeys.IS_CHAT_NOTIFICATIONS_ENABLED.putValue(
                    preferenceProvider = provider,
                    newValue = true,
                )
            }
            StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED.putValue(
                preferenceProvider = provider,
                newValue = preferences.isBackgroundDeliveryEnabled,
            )
            pushRegistrar.sync()
            navigationRouter.back()
        }
    }

    private fun onBack() = navigationRouter.back()
}

private val DEFAULT_PREFERENCES =
    ChatPreferences(
        isReadReceiptsEnabled = true,
        isOnlineStatusEnabled = true,
        isBackgroundDeliveryEnabled = false,
    )
