// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.walletaddress

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOfframpBaseAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.common.CopyFeedback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatWalletAddressVM(
    private val copyToClipboard: CopyToClipboardUseCase,
    private val getOfframpBaseAddress: GetOfframpBaseAddressUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val baseAddress = MutableStateFlow<String?>(null)
    private val copyFeedback = CopyFeedback(viewModelScope)

    private val walletAccount =
        observeSelectedWalletAccount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    init {
        viewModelScope.launch {
            runCatching { getOfframpBaseAddress() }
                .onSuccess { address -> baseAddress.value = address }
                .onFailure { Twig.warn(it) { "ChatWalletAddressVM: base address resolve failed" } }
        }
    }

    val state: StateFlow<ChatWalletAddressState> =
        combine(walletAccount, baseAddress, copyFeedback.copiedValue) { wallet, base, copied ->
            createState(wallet, base, copied)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(wallet = null, base = null, copied = null),
        )

    private fun createState(
        wallet: WalletAccount?,
        base: String?,
        copied: String?,
    ) = ChatWalletAddressState(
        addresses =
            listOfNotNull(
                wallet?.unified?.address?.address?.let { address ->
                    item(
                        label = R.string.chat_profile_address_shielded_label,
                        caption = R.string.chat_profile_address_shielded_caption,
                        address = address,
                        hasQrCode = true,
                        copied = copied,
                    )
                },
                wallet?.transparent?.address?.address?.let { address ->
                    item(
                        label = R.string.chat_profile_address_transparent_label,
                        caption = R.string.chat_profile_address_transparent_caption,
                        address = address,
                        hasQrCode = true,
                        copied = copied,
                    )
                },
                base?.let { address ->
                    item(
                        label = R.string.chat_profile_address_base_label,
                        caption = R.string.chat_profile_address_base_caption,
                        address = address,
                        hasQrCode = false,
                        copied = copied,
                    )
                },
            ),
        onBack = ::onBack,
    )

    private fun item(
        @StringRes label: Int,
        @StringRes caption: Int,
        address: String,
        hasQrCode: Boolean,
        copied: String?,
    ) = ChatWalletAddressItem(
        label = stringRes(label),
        caption = stringRes(caption),
        address = address,
        hasQrCode = hasQrCode,
        isCopied = copied == address,
        onCopyClick = { onCopyClick(address) },
    )

    private fun onCopyClick(address: String) {
        copyToClipboard(address, isSensitive = false)
        copyFeedback.mark(address)
    }

    private fun onBack() = navigationRouter.back()

    override fun onCleared() {
        super.onCleared()
        copyFeedback.cancel()
    }
}
