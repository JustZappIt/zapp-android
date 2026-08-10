// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.p2pkey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ExportP2pWalletKeyUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOfframpBaseAddressUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.common.CopyFeedback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatP2pKeyVM(
    private val copyToClipboard: CopyToClipboardUseCase,
    private val exportP2pWalletKey: ExportP2pWalletKeyUseCase,
    private val getOfframpBaseAddress: GetOfframpBaseAddressUseCase,
    private val secretAuthGate: SecretAuthGate,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val smartAccountAddress = MutableStateFlow<String?>(null)
    private val ownerKey = MutableStateFlow<ChatP2pOwnerKey?>(null)
    private val copyFeedback = CopyFeedback(viewModelScope)

    init {
        viewModelScope.launch {
            runCatching { getOfframpBaseAddress() }
                .onSuccess { address -> smartAccountAddress.value = address }
                .onFailure { Twig.warn(it) { "ChatP2pKeyVM: smart account address resolve failed" } }
        }
    }

    val state: StateFlow<ChatP2pKeyState> =
        combine(
            smartAccountAddress,
            ownerKey,
            copyFeedback.copiedValue,
            secretAuthGate.pinPrompt,
        ) { account, key, copied, pin ->
            createState(account, key, copied, pin)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(account = null, key = null, copied = null, pin = null),
        )

    private fun createState(
        account: String?,
        key: ChatP2pOwnerKey?,
        copied: String?,
        pin: PinVerifyState?,
    ) = ChatP2pKeyState(
        smartAccountAddress = account,
        ownerKey = key,
        copiedValue = copied,
        onCopyClick = ::onCopyClick,
        onRevealClick = ::onRevealClick,
        onBack = ::onBack,
        pinVerify = pin,
    )

    private fun onRevealClick() {
        if (ownerKey.value != null) return
        viewModelScope.launch {
            if (!secretAuthGate.authenticate(stringRes(R.string.chat_profile_p2p_key_biometric_prompt))) return@launch
            runCatching { exportP2pWalletKey() }
                .onSuccess { key ->
                    ownerKey.value = ChatP2pOwnerKey(address = key.address, privateKeyHex = key.privateKeyHex)
                }.onFailure { Twig.warn(it) { "ChatP2pKeyVM: P2P wallet key export failed" } }
        }
    }

    private fun onCopyClick(value: String) {
        val isPrivateKey = value == ownerKey.value?.privateKeyHex
        copyToClipboard(value, isSensitive = isPrivateKey)
        copyFeedback.mark(value)
    }

    private fun onBack() = navigationRouter.back()

    override fun onCleared() {
        super.onCleared()
        copyFeedback.cancel()
    }
}
