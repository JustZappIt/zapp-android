// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.settings

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.list.ChatListConnectionStatus
import co.electriccoin.zcash.ui.screen.chat.list.ChatListDhtHealth

data class ChatSettingsState(
    val title: StringResource,
    val deleteLabel: StringResource,
    val displayName: String?,
    val publicKey: String?,
    val isPublicKeyCopied: Boolean,
    val connectionStatus: ChatListConnectionStatus,
    val dhtHealth: ChatListDhtHealth,
    val peerCount: Int,
    val notificationsEnabled: Boolean,
    val backgroundPushEnabled: Boolean,
    val onProfileClick: () -> Unit,
    val onContactsClick: () -> Unit,
    val onEditDisplayNameClick: () -> Unit,
    val onCopyPublicKeyClick: () -> Unit,
    val onDeleteClick: () -> Unit,
    val onNotificationsToggle: (Boolean) -> Unit,
    val onBackgroundPushToggle: (Boolean) -> Unit,
    val onReadReceiptsClick: () -> Unit,
    val onOnlineStatusClick: () -> Unit,
    val onBack: () -> Unit,
    val editNameDialog: ChatSettingsEditNameDialogState?,
    val deleteDialog: ChatSettingsDeleteDialogState?,
)

data class ChatSettingsEditNameDialogState(
    val value: String,
    val canSave: Boolean,
    val isSaving: Boolean,
    val error: StringResource?,
    val onValueChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatSettingsDeleteDialogState(
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)
