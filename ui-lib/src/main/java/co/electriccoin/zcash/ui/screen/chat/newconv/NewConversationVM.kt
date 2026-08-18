// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.newconv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.CreateChatGroupUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrCreateChatConversationUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanPublicKeyUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class NewConversationVM(
    private val chatContactsRepository: ChatContactsRepository,
    private val getOrCreateChatConversation: GetOrCreateChatConversationUseCase,
    private val createChatGroup: CreateChatGroupUseCase,
    private val navigateToScanPublicKey: NavigateToScanPublicKeyUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val searchInput = MutableStateFlow("")
    private val selectedParticipants = MutableStateFlow<List<SelectedParticipant>>(emptyList())
    private val isCreating = MutableStateFlow(false)

    // Non-null while the group-name prompt is open; its value is the draft name.
    private val groupNameDraft = MutableStateFlow<String?>(null)
    private val rejoinTarget = MutableStateFlow<SelectedParticipant?>(null)

    val state: StateFlow<NewConversationState> =
        combine(
            searchInput,
            selectedParticipants,
            chatContactsRepository.contacts,
            combine(isCreating, groupNameDraft, rejoinTarget) { creating, groupName, rejoin ->
                DialogSnapshot(creating, groupName, rejoin)
            },
        ) { input, participants, contactList, dialog ->
            createState(input, participants, contactList, dialog)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    input = "",
                    participants = emptyList(),
                    contactList = emptyList(),
                    dialog = DialogSnapshot(creating = false, groupName = null, rejoinTarget = null),
                ),
        )

    private fun createState(
        input: String,
        participants: List<SelectedParticipant>,
        contactList: List<ChatContact>,
        dialog: DialogSnapshot,
    ): NewConversationState {
        val creating = dialog.creating
        val trimmed = input.trim()
        val cleanedSearch = trimmed.removePrefix("0x")
        val isPublicKey =
            cleanedSearch.length == PUBLIC_KEY_HEX_LENGTH &&
                cleanedSearch.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        // Blocked contacts are excluded: starting a chat with one silently drops their replies.
        val visible = contactList.filterNot { it.isBlocked }
        val filtered =
            if (trimmed.isEmpty()) {
                visible
            } else {
                visible.filter {
                    it.name.contains(trimmed, ignoreCase = true) ||
                        it.publicKey.contains(trimmed, ignoreCase = true)
                }
            }

        val showEmptyState = trimmed.isEmpty() && participants.isEmpty()
        // Stays StartChat while [creating] so the dock can show its spinner; flipping to ScanQr
        // mid-create would hide the loader and swap the label.
        val canStartChat = participants.isNotEmpty() || creating

        val primaryAction =
            if (canStartChat) {
                NewConversationPrimaryAction.StartChat(
                    isCreating = creating,
                    onClick = ::onStartChatClick,
                )
            } else {
                NewConversationPrimaryAction.ScanQr(onClick = ::onScanQrClick)
            }

        return NewConversationState(
            title = stringRes(R.string.chat_new_conversation_title),
            searchInput = input,
            onSearchInputChange = ::onSearchInputChange,
            onClearSearch = ::onClearSearch,
            isPublicKeyDetected =
                isPublicKey && participants.none { it.publicKey == cleanedSearch },
            detectedPublicKey = cleanedSearch,
            onAddDetectedKey = ::onAddDetectedKey,
            selectedParticipants =
                participants.map { p ->
                    NewConversationParticipantChip(
                        publicKey = p.publicKey,
                        displayName = p.displayName,
                        onRemove = { removeParticipant(p.publicKey) },
                    )
                },
            contacts =
                filtered
                    .sortedBy { it.name.lowercase() }
                    .map { c ->
                        val selected = participants.any { it.publicKey == c.publicKey }
                        NewConversationContactItem(
                            contact = c,
                            isSelected = selected,
                            onToggle = { onContactToggle(c, selected) },
                        )
                    },
            showEmptyState = showEmptyState,
            primaryAction = primaryAction,
            groupNameDialog =
                dialog.groupName?.let {
                    NewConversationGroupNameDialogState(
                        value = it,
                        canConfirm = it.isNotBlank() && !creating,
                        onValueChange = ::onGroupNameChange,
                        onConfirm = ::onGroupNameConfirm,
                        onDismiss = ::onGroupNameDismiss,
                    )
                },
            rejoinDialog =
                dialog.rejoinTarget?.let { target ->
                    NewConversationRejoinDialogState(
                        displayName = target.displayName,
                        onConfirm = ::onRejoinConfirm,
                        onDismiss = ::onRejoinDismiss,
                    )
                },
            onBack = ::onBack,
        )
    }

    private fun onSearchInputChange(value: String) {
        searchInput.value = value
    }

    private fun onClearSearch() {
        searchInput.value = ""
    }

    private fun onAddDetectedKey() {
        val cleaned = searchInput.value.trim().removePrefix("0x")
        if (cleaned.length != PUBLIC_KEY_HEX_LENGTH) return
        val existing = chatContactsRepository.contacts.value.firstOrNull { it.publicKey == cleaned }
        val displayName = existing?.name ?: "${cleaned.take(KEY_PREVIEW_HEAD)}..."
        addParticipant(cleaned, displayName)
        searchInput.value = ""
    }

    private fun addParticipant(publicKey: String, displayName: String) {
        if (isCreating.value) return
        if (selectedParticipants.value.any { it.publicKey == publicKey }) return
        selectedParticipants.value =
            selectedParticipants.value + SelectedParticipant(publicKey, displayName)
    }

    private fun removeParticipant(publicKey: String) {
        if (isCreating.value) return
        selectedParticipants.value = selectedParticipants.value.filter { it.publicKey != publicKey }
    }

    private fun onContactToggle(contact: ChatContact, isSelected: Boolean) {
        if (isSelected) {
            removeParticipant(contact.publicKey)
        } else {
            addParticipant(contact.publicKey, contact.name)
        }
    }

    private fun onStartChatClick() {
        if (isCreating.value) return
        val selected = selectedParticipants.value
        when {
            selected.isEmpty() -> {
                return
            }

            // >1 participant becomes a group; prompt for a name before creating.
            selected.size > 1 -> {
                groupNameDraft.value = ""
            }

            else -> {
                val first = selected.first()
                viewModelScope.launch {
                    createDirectChat(first.publicKey, first.displayName, confirmRejoin = true)
                }
            }
        }
    }

    private fun onGroupNameChange(value: String) {
        if (groupNameDraft.value != null) groupNameDraft.value = value
    }

    private fun onGroupNameDismiss() {
        groupNameDraft.value = null
    }

    private fun onGroupNameConfirm() {
        if (isCreating.value) return
        val name = groupNameDraft.value?.trim().orEmpty()
        val participants = selectedParticipants.value
        if (name.isEmpty() || participants.size < 2) return
        viewModelScope.launch { createGroupChat(name, participants) }
    }

    private fun onRejoinConfirm() {
        val target = rejoinTarget.value ?: return
        rejoinTarget.value = null
        viewModelScope.launch {
            createDirectChat(target.publicKey, target.displayName, confirmRejoin = false)
        }
    }

    private fun onRejoinDismiss() {
        rejoinTarget.value = null
    }

    private suspend fun createDirectChat(
        publicKey: String,
        displayName: String,
        confirmRejoin: Boolean,
    ) {
        isCreating.value = true
        try {
            if (confirmRejoin && getOrCreateChatConversation.hasLeftDirectConversation(publicKey)) {
                rejoinTarget.value = SelectedParticipant(publicKey, displayName)
                return
            }
            // Reuses an existing 1:1 conversation instead of creating a duplicate — same
            // dedupe the Request wizard's "Send in chat" gets.
            val conversationId = getOrCreateChatConversation(publicKey, displayName)
            if (conversationId != null) {
                navigationRouter.replace(ChatRoomArgs(conversationId))
            }
        } finally {
            isCreating.value = false
        }
    }

    private suspend fun createGroupChat(name: String, participants: List<SelectedParticipant>) {
        isCreating.value = true
        try {
            createChatGroup(name, participants.map { it.publicKey })
                .onSuccess { conversationId ->
                    groupNameDraft.value = null
                    navigationRouter.replace(ChatRoomArgs(conversationId))
                }
        } finally {
            isCreating.value = false
        }
    }

    private fun onScanQrClick() {
        viewModelScope.launch {
            val key = navigateToScanPublicKey()
            if (!key.isNullOrBlank()) searchInput.value = key
        }
    }

    private fun onBack() = navigationRouter.back()

    private data class SelectedParticipant(
        val publicKey: String,
        val displayName: String
    )

    private data class DialogSnapshot(
        val creating: Boolean,
        val groupName: String?,
        val rejoinTarget: SelectedParticipant?,
    )

    companion object {
        private const val PUBLIC_KEY_HEX_LENGTH = 64
        private const val KEY_PREVIEW_HEAD = 8
    }
}
