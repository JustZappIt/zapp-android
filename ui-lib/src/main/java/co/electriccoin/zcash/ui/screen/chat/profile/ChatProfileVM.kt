// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.profile

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteChatIdentityUseCase
import co.electriccoin.zcash.ui.common.usecase.ExportChatSeedPhraseUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatIdentityUseCase
import co.electriccoin.zcash.ui.common.usecase.UpdateChatDisplayNameUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.common.ChatResult
import co.electriccoin.zcash.ui.screen.chat.common.CopyFeedback
import co.electriccoin.zcash.ui.screen.chat.common.UsernameRules
import co.electriccoin.zcash.ui.screen.chat.p2pkey.ChatP2pKeyArgs
import co.electriccoin.zcash.ui.screen.chat.walletaddress.ChatWalletAddressArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class ChatProfileVM(
    private val application: Application,
    private val copyToClipboard: CopyToClipboardUseCase,
    observeChatIdentity: ObserveChatIdentityUseCase,
    private val updateChatDisplayName: UpdateChatDisplayNameUseCase,
    private val deleteChatIdentity: DeleteChatIdentityUseCase,
    private val exportChatSeedPhrase: ExportChatSeedPhraseUseCase,
    private val secretAuthGate: SecretAuthGate,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val copyFeedback = CopyFeedback(viewModelScope)
    private val showDeleteDialog = MutableStateFlow(false)
    private val showEditNameDialog = MutableStateFlow(false)
    private val editNameInput = MutableStateFlow("")
    private val isUpdatingDisplayName = MutableStateFlow(false)
    private val editNameError = MutableStateFlow<StringResource?>(null)
    private val pendingSeedPhrase = MutableStateFlow<String?>(null)

    private val identity =
        observeChatIdentity()
            .map { id -> id?.let { ChatProfileIdentity(displayName = it.displayName, publicKey = it.publicKey) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    val state: StateFlow<ChatProfileState> =
        combine(
            identity,
            copyFeedback.copiedValue,
            combine(
                showDeleteDialog,
                showEditNameDialog,
                editNameInput,
                isUpdatingDisplayName,
                editNameError,
            ) { delete, edit, input, isSaving, error ->
                DialogSnapshot(delete, edit, input, isSaving, error)
            },
            combine(secretAuthGate.pinPrompt, pendingSeedPhrase) { pin, seed -> pin to seed },
        ) { id, copiedValue, dialogs, (pinPrompt, seed) ->
            createState(id, copiedValue, dialogs, pinPrompt, seed)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    id = null,
                    copiedValue = null,
                    dialogs =
                        DialogSnapshot(
                            showDelete = false,
                            showEdit = false,
                            input = "",
                            isSaving = false,
                            error = null,
                        ),
                    pinPrompt = null,
                    seed = null,
                ),
        )

    private fun createState(
        id: ChatProfileIdentity?,
        copiedValue: String?,
        dialogs: DialogSnapshot,
        pinPrompt: PinVerifyState?,
        seed: String?,
    ): ChatProfileState =
        ChatProfileState(
            title = stringRes(R.string.chat_profile_title),
            displayName = id?.displayName,
            publicKey = id?.publicKey,
            isKeyCopied = copiedValue != null && copiedValue == id?.publicKey,
            onEditDisplayNameClick = ::onEditDisplayNameClick,
            onCopyPublicKeyClick = ::onCopyPublicKeyClick,
            onWalletAddressClick = ::onWalletAddressClick,
            onSeedPhraseClick = ::onSeedPhraseClick,
            onP2pKeyClick = ::onP2pKeyClick,
            onDeleteClick = ::onDeleteClick,
            onBack = ::onBack,
            editNameDialog =
                if (dialogs.showEdit) {
                    ChatProfileEditNameDialogState(
                        value = dialogs.input,
                        canSave = UsernameRules.isValid(dialogs.input) && !dialogs.isSaving,
                        isSaving = dialogs.isSaving,
                        error = dialogs.error,
                        onValueChange = ::onEditNameInputChange,
                        onSave = ::onEditNameSave,
                        onDismiss = ::dismissEditNameDialog,
                    )
                } else {
                    null
                },
            deleteDialog =
                if (dialogs.showDelete) {
                    ChatProfileDeleteDialogState(
                        onConfirm = ::onDeleteConfirm,
                        onDismiss = ::dismissDeleteDialog,
                    )
                } else {
                    null
                },
            seedPhraseDialog =
                seed?.let { phrase ->
                    ChatProfileSeedPhraseDialogState(
                        words = phrase.split(" ").filter { it.isNotBlank() },
                        onDismiss = ::dismissSeedPhraseDialog,
                    )
                },
            pinVerify = pinPrompt,
        )

    // ── Click handlers ─────────────────────────────────────────────────

    private fun onBack() = navigationRouter.back()

    private fun onWalletAddressClick() = navigationRouter.forward(ChatWalletAddressArgs)

    private fun onEditDisplayNameClick() {
        editNameInput.value = UsernameRules.sanitize(identity.value?.displayName.orEmpty())
        editNameError.value = null
        showEditNameDialog.value = true
    }

    private fun onEditNameInputChange(value: String) {
        editNameInput.value = UsernameRules.sanitize(value)
        editNameError.value = null
    }

    private fun onEditNameSave() {
        val trimmed = editNameInput.value.trim()
        if (!UsernameRules.isValid(trimmed) || isUpdatingDisplayName.value) return
        isUpdatingDisplayName.value = true
        editNameError.value = null
        viewModelScope.launch {
            try {
                updateChatDisplayName(trimmed)
                    .onSuccess { showEditNameDialog.value = false }
                    .onFailure { editNameError.value = stringRes(R.string.chat_display_name_update_error) }
            } finally {
                isUpdatingDisplayName.value = false
            }
        }
    }

    private fun dismissEditNameDialog() {
        if (isUpdatingDisplayName.value) return
        showEditNameDialog.value = false
        editNameError.value = null
    }

    private fun onDeleteClick() {
        showDeleteDialog.value = true
    }

    private fun dismissDeleteDialog() {
        showDeleteDialog.value = false
    }

    private fun onDeleteConfirm() {
        showDeleteDialog.value = false
        viewModelScope.launch { performDeleteIdentity() }
    }

    private suspend fun performDeleteIdentity() {
        deleteChatIdentity()
        application.packageManager.getLaunchIntentForPackage(application.packageName)?.let { intent ->
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            application.startActivity(intent)
        }
        Process.killProcess(Process.myPid())
    }

    private fun onCopyPublicKeyClick() {
        val publicKey = identity.value?.publicKey ?: return
        copyToClipboard(publicKey, isSensitive = false)
        copyFeedback.mark(publicKey)
    }

    // ── Secret reveal (app-lock gate) ─────────────────────────────────

    private fun onSeedPhraseClick() {
        viewModelScope.launch {
            if (secretAuthGate.authenticate(stringRes(R.string.chat_profile_seed_phrase_biometric_prompt))) {
                val result = exportChatSeedPhrase()
                if (result is ChatResult.Success) pendingSeedPhrase.value = result.value
            }
        }
    }

    private fun onP2pKeyClick() = navigationRouter.forward(ChatP2pKeyArgs)

    private fun dismissSeedPhraseDialog() {
        pendingSeedPhrase.value = null
    }

    private data class ChatProfileIdentity(
        val displayName: String,
        val publicKey: String
    )

    private data class DialogSnapshot(
        val showDelete: Boolean,
        val showEdit: Boolean,
        val input: String,
        val isSaving: Boolean,
        val error: StringResource?,
    )

    override fun onCleared() {
        super.onCleared()
        copyFeedback.cancel()
    }
}
