// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.push.PushRegistrar
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.UpdateChatDisplayNameUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.chat.ChatContactsArgs
import co.electriccoin.zcash.ui.screen.chat.ChatProfileArgs
import co.electriccoin.zcash.ui.screen.chat.common.UsernameRules
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.chat.list.ChatListConnectionStatus
import co.electriccoin.zcash.ui.screen.chat.list.ChatListDhtHealth
import co.electriccoin.zcash.ui.screen.chat.list.mapDhtHealth
import co.electriccoin.zcash.ui.screen.chat.onlinestatus.OnlineStatusSettingsArgs
import co.electriccoin.zcash.ui.screen.chat.readreceipts.ReadReceiptsSettingsArgs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.justzappit.zappmessaging.ZappMessagingSDK

@Suppress("TooManyFunctions")
class ChatSettingsVM(
    private val copyToClipboard: CopyToClipboardUseCase,
    private val sdk: ZappMessagingSDK,
    private val updateChatDisplayName: UpdateChatDisplayNameUseCase,
    private val navigationRouter: NavigationRouter,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val pushRegistrar: PushRegistrar,
) : ViewModel() {
    private val connectionStatus = MutableStateFlow(ChatListConnectionStatus.CONNECTING)
    private val peerCount = MutableStateFlow(0)
    private val dhtHealth = MutableStateFlow(ChatListDhtHealth.HEALTHY)

    private val showEditNameDialog = MutableStateFlow(false)
    private val editNameInput = MutableStateFlow("")
    private val isUpdatingDisplayName = MutableStateFlow(false)
    private val editNameError = MutableStateFlow<StringResource?>(null)
    private val showDeleteConfirm = MutableStateFlow(false)
    private val isPublicKeyCopied = MutableStateFlow(false)
    private var copyResetJob: Job? = null

    private val identity =
        sdk.identity
            .map { id -> id?.let { ChatSettingsIdentity(displayName = it.displayName, publicKey = it.publicKey) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private val notificationsEnabled: StateFlow<Boolean> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_NOTIFICATIONS_ENABLED.observe(standardPreferenceProvider()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = false,
        )

    private val backgroundPushEnabled: StateFlow<Boolean> =
        flow {
            emitAll(StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED.observe(standardPreferenceProvider()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = false,
        )

    init {
        observeConnection()
    }

    val state: StateFlow<ChatSettingsState> =
        combine(
            combine(identity, isPublicKeyCopied) { id, copied -> id to copied },
            combine(connectionStatus, peerCount, dhtHealth) { cs, pc, dh -> Triple(cs, pc, dh) },
            combine(
                showEditNameDialog,
                editNameInput,
                showDeleteConfirm,
                isUpdatingDisplayName,
                editNameError,
            ) { edit, input, delete, isSaving, error ->
                EditNameSnapshot(edit, input, delete, isSaving, error)
            },
            notificationsEnabled,
            backgroundPushEnabled,
        ) { (id, copied), (cs, pc, dh), editName, notifEnabled, pushEnabled ->
            createState(id, copied, cs, pc, dh, editName, notifEnabled, pushEnabled)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    id = null,
                    copied = false,
                    cs = ChatListConnectionStatus.CONNECTING,
                    pc = 0,
                    dh = ChatListDhtHealth.HEALTHY,
                    editName = EditNameSnapshot(),
                    notifEnabled = true,
                    pushEnabled = false,
                ),
        )

    private fun createState(
        id: ChatSettingsIdentity?,
        copied: Boolean,
        cs: ChatListConnectionStatus,
        pc: Int,
        dh: ChatListDhtHealth,
        editName: EditNameSnapshot,
        notifEnabled: Boolean,
        pushEnabled: Boolean,
    ): ChatSettingsState =
        ChatSettingsState(
            title = stringRes(R.string.chat_settings_title),
            deleteLabel = stringRes(R.string.chat_settings_delete_button),
            displayName = id?.displayName,
            publicKey = id?.publicKey,
            isPublicKeyCopied = copied,
            connectionStatus = cs,
            dhtHealth = dh,
            peerCount = pc,
            notificationsEnabled = notifEnabled,
            backgroundPushEnabled = pushEnabled,
            onProfileClick = ::onProfileClick,
            onContactsClick = ::onContactsClick,
            onEditDisplayNameClick = ::onEditDisplayNameClick,
            onCopyPublicKeyClick = ::onCopyPublicKeyClick,
            onDeleteClick = ::onDeleteClick,
            onNotificationsToggle = ::onNotificationsToggle,
            onBackgroundPushToggle = ::onBackgroundPushToggle,
            onReadReceiptsClick = ::onReadReceiptsClick,
            onOnlineStatusClick = ::onOnlineStatusClick,
            onBack = ::onBack,
            editNameDialog =
                if (editName.showEdit) {
                    ChatSettingsEditNameDialogState(
                        value = editName.input,
                        canSave = UsernameRules.isValid(editName.input) && !editName.isSaving,
                        isSaving = editName.isSaving,
                        error = editName.error,
                        onValueChange = ::onEditNameInputChange,
                        onSave = ::onEditNameSave,
                        onDismiss = ::dismissEditNameDialog,
                    )
                } else {
                    null
                },
            deleteDialog =
                if (editName.showDelete) {
                    ChatSettingsDeleteDialogState(
                        onConfirm = ::onDeleteConfirm,
                        onDismiss = ::dismissDeleteDialog,
                    )
                } else {
                    null
                },
        )

    private fun observeConnection() {
        viewModelScope.launch {
            sdk.isOnline.collect { online ->
                connectionStatus.value =
                    if (online) {
                        ChatListConnectionStatus.CONNECTED
                    } else {
                        ChatListConnectionStatus.DISCONNECTED
                    }
            }
        }
        viewModelScope.launch { sdk.peerCount.collect { peerCount.value = it } }
        viewModelScope.launch { sdk.dhtHealth.collect { dhtHealth.value = mapDhtHealth(it) } }
    }

    private fun onBack() = navigationRouter.back()

    private fun onProfileClick() = navigationRouter.forward(ChatProfileArgs)

    private fun onContactsClick() = navigationRouter.forward(ChatContactsArgs)

    private fun onReadReceiptsClick() = navigationRouter.forward(ReadReceiptsSettingsArgs)

    private fun onOnlineStatusClick() = navigationRouter.forward(OnlineStatusSettingsArgs)

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
        showDeleteConfirm.value = true
    }

    private fun dismissDeleteDialog() {
        showDeleteConfirm.value = false
    }

    private fun onDeleteConfirm() {
        showDeleteConfirm.value = false
        viewModelScope.launch { performDeleteIdentity() }
    }

    private suspend fun performDeleteIdentity() {
        runChatCall("ChatSettingsVM: sdk.shutdown failed") {
            sdk.shutdown()
        }
        navigationRouter.backToRoot()
    }

    private fun onCopyPublicKeyClick() {
        val pk = identity.value?.publicKey ?: return
        copyToClipboard(pk)
        isPublicKeyCopied.value = true
        copyResetJob?.cancel()
        copyResetJob =
            viewModelScope.launch {
                delay(COPY_FEEDBACK_MS)
                isPublicKeyCopied.value = false
            }
    }

    private fun onNotificationsToggle(enabled: Boolean) {
        viewModelScope.launch {
            StandardPreferenceKeys.IS_CHAT_NOTIFICATIONS_ENABLED.putValue(
                preferenceProvider = standardPreferenceProvider(),
                newValue = enabled,
            )
            if (!enabled) {
                StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED.putValue(
                    preferenceProvider = standardPreferenceProvider(),
                    newValue = false,
                )
            }
            pushRegistrar.sync()
        }
    }

    private fun onBackgroundPushToggle(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                StandardPreferenceKeys.IS_CHAT_NOTIFICATIONS_ENABLED.putValue(
                    preferenceProvider = standardPreferenceProvider(),
                    newValue = true,
                )
            }
            StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED.putValue(
                preferenceProvider = standardPreferenceProvider(),
                newValue = enabled,
            )
            pushRegistrar.sync()
        }
    }

    override fun onCleared() {
        super.onCleared()
        copyResetJob?.cancel()
    }

    private data class ChatSettingsIdentity(
        val displayName: String,
        val publicKey: String
    )

    private data class EditNameSnapshot(
        val showEdit: Boolean = false,
        val input: String = "",
        val showDelete: Boolean = false,
        val isSaving: Boolean = false,
        val error: StringResource? = null,
    )

    companion object {
        private const val COPY_FEEDBACK_MS = 2_000L
    }
}
