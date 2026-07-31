// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.backgrounddelivery

data class BackgroundDeliverySettingsState(
    val isEnabled: Boolean,
    val onSaveClick: (isEnabled: Boolean) -> Unit,
    val onBack: () -> Unit,
)
