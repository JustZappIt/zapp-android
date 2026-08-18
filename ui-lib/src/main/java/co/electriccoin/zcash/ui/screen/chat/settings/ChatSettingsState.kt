// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.settings

data class ChatSettingsState(
    val preferences: ChatPreferences,
    val onSaveClick: (ChatPreferences) -> Unit,
    val onBack: () -> Unit,
)

data class ChatPreferences(
    val isReadReceiptsEnabled: Boolean,
    val isOnlineStatusEnabled: Boolean,
    val isBackgroundDeliveryEnabled: Boolean,
)
