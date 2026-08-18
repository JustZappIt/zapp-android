// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.buildSetupDiagnostic
import co.electriccoin.zcash.ui.common.usecase.ObserveChatIdentityUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.chat.common.UsernameRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ChatIdentitySetupVM(
    observeChatIdentity: ObserveChatIdentityUseCase,
    private val chatBootstrap: ChatBootstrap,
) : ViewModel() {
    // Pre-fill the display name from the pending name captured during onboarding
    // so the user doesn't have to retype it after a failed auto-derive.
    private val displayName = MutableStateFlow(chatBootstrap.pendingDisplayName.value ?: "")
    private val error = MutableStateFlow<StringResource?>(null)

    val isSetupComplete: StateFlow<Boolean> =
        observeChatIdentity()
            .map { it != null }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = false,
            )

    val state: StateFlow<ChatIdentitySetupState> =
        combine(
            displayName,
            error,
            chatBootstrap.isDeriving,
            chatBootstrap.chatIdentityErrorCode,
        ) { name, err, isDeriving, errorCode ->
            buildState(name, err, isDeriving, errorCode)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = buildState(displayName.value, error = null, isSubmitting = false, errorCode = null),
        )

    private fun buildState(
        name: String,
        error: StringResource?,
        isSubmitting: Boolean,
        errorCode: String?,
    ): ChatIdentitySetupState =
        ChatIdentitySetupState(
            title = stringRes(R.string.chat_identity_setup_wallet_title),
            subtitle = stringRes(R.string.chat_identity_setup_wallet_subtitle),
            displayName = name,
            displayNamePlaceholder = stringRes(R.string.chat_identity_setup_display_name_placeholder),
            submitLabel = stringRes(R.string.chat_identity_setup_wallet_button),
            isSubmitting = isSubmitting,
            error =
                when {
                    error != null -> error
                    errorCode != null -> stringRes(R.string.chat_identity_setup_error_wallet_derive_failed)
                    else -> null
                },
            diagnostic = errorCode?.let { buildSetupDiagnostic(operation = "chat-identity setup", code = it) },
            onDisplayNameChange = ::onDisplayNameChange,
            onSubmit = ::onSubmit,
        )

    // Sanitize on every keystroke so the field only ever holds chars the create path
    // (onboarding's UsernameEntryScreen) would have accepted — the two paths must agree
    // on the on-disk name shape (see UsernameRules).
    private fun onDisplayNameChange(value: String) {
        displayName.value = UsernameRules.sanitize(value)
    }

    // Hand the display name to ChatBootstrap, which derives the identity from the wallet
    // seed reactively. retry() forces a fresh attempt when a previous auto-derive failed
    // with the same name — setPendingDisplayName alone wouldn't re-fire on an unchanged name.
    private fun onSubmit() {
        val name = displayName.value.trim()
        if (!UsernameRules.isValid(name)) {
            error.value = stringRes(R.string.chat_identity_setup_error_name_invalid)
            return
        }
        error.value = null
        chatBootstrap.setPendingDisplayName(name)
        if (chatBootstrap.chatIdentityFailed.value) {
            chatBootstrap.retry()
        }
    }
}
