// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.newconv

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact

data class NewConversationState(
    val title: StringResource,
    val searchInput: String,
    val onSearchInputChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val isPublicKeyDetected: Boolean,
    val detectedPublicKey: String,
    val onAddDetectedKey: () -> Unit,
    val selectedParticipants: List<NewConversationParticipantChip>,
    val contacts: List<NewConversationContactItem>,
    val showEmptyState: Boolean,
    val primaryAction: NewConversationPrimaryAction,
    val groupNameDialog: NewConversationGroupNameDialogState? = null,
    val rejoinDialog: NewConversationRejoinDialogState? = null,
    val onBack: () -> Unit,
)

data class NewConversationGroupNameDialogState(
    val value: String,
    val canConfirm: Boolean,
    val onValueChange: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

data class NewConversationRejoinDialogState(
    val displayName: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

data class NewConversationParticipantChip(
    val publicKey: String,
    val displayName: String,
    val onRemove: () -> Unit,
)

data class NewConversationContactItem(
    val contact: ChatContact,
    val isSelected: Boolean,
    val onToggle: () -> Unit,
)

sealed interface NewConversationPrimaryAction {
    val onClick: () -> Unit

    data class ScanQr(
        override val onClick: () -> Unit
    ) : NewConversationPrimaryAction

    data class StartChat(
        val isCreating: Boolean,
        override val onClick: () -> Unit,
    ) : NewConversationPrimaryAction
}
