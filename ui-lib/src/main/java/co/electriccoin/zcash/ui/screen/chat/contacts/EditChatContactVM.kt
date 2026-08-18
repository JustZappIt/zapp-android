// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.contacts

import androidx.compose.ui.text.input.TextFieldValue
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-sheet ViewModel for the "Edit chat contact" flow. Loaded with a
 * [ChatContact] as its seed; owns all form state thereafter.
 *
 * Follows the upstream `UpdateGenericABContactVM` pattern (state.stateIn with
 * `SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT)`), adapted for
 * the fork's `ZappInputField` + `TextFieldValue` rendering.
 */
class EditChatContactVM(
    private val contact: ChatContact,
    private val scope: CoroutineScope,
    private val scannedWalletAddressFlow: StateFlow<String?>,
    private val onConsumeScannedWalletAddress: () -> Unit,
    private val onScanWalletAddressRequest: () -> Unit,
    private val onSaveContact: suspend (
        publicKey: String,
        name: String,
        walletAddress: String,
        walletAddresses: Map<String, String>,
    ) -> Boolean,
    private val onDeleteContact: suspend (publicKey: String) -> Boolean,
    private val onValidateWalletAddress: suspend (String) -> Boolean,
    private val onDismissRequest: () -> Unit,
    private val onBlock: (() -> Unit)? = null,
    initialWalletAddresses: Map<String, String> = emptyMap(),
    // False when the sheet is seeded for a chat-room peer with no saved row yet: saving is then
    // allowed without edits, and there is nothing to delete.
    private val isSaved: Boolean = true,
) {
    private val initialTransparent = initialWalletAddresses[AddressBookContact.ADDR_TYPE_TRANSPARENT].orEmpty()
    private val initialEvm = initialWalletAddresses[AddressBookContact.ADDR_TYPE_EVM].orEmpty()
    private val initialSolana = initialWalletAddresses[AddressBookContact.ADDR_TYPE_SOLANA].orEmpty()

    private val name = MutableStateFlow(TextFieldValue(contact.name))
    private val walletAddress = MutableStateFlow(TextFieldValue(contact.address.orEmpty()))
    private val transparentAddr =
        MutableStateFlow(TextFieldValue(initialWalletAddresses[AddressBookContact.ADDR_TYPE_TRANSPARENT].orEmpty()))
    private val evmAddr =
        MutableStateFlow(TextFieldValue(initialWalletAddresses[AddressBookContact.ADDR_TYPE_EVM].orEmpty()))
    private val solanaAddr =
        MutableStateFlow(TextFieldValue(initialWalletAddresses[AddressBookContact.ADDR_TYPE_SOLANA].orEmpty()))
    private val showAdditionalAddresses = MutableStateFlow(initialWalletAddresses.isNotEmpty())
    private val showDeleteConfirm = MutableStateFlow(false)
    private val error = MutableStateFlow<StringResource?>(null)

    private val scanTargetField = MutableStateFlow<String?>(null)

    // Eagerly collected on the parent scope, so it must be cancelled in [close] when the sheet
    // dismisses — otherwise a collector leaks per open/close cycle.
    private val scanCollectorJob: Job =
        scannedWalletAddressFlow
            .onEach { addr ->
                if (!addr.isNullOrEmpty()) {
                    val target = scanTargetField.value
                    if (target != null) {
                        val tfv = TextFieldValue(addr)
                        when (target) {
                            AddressBookContact.ADDR_TYPE_TRANSPARENT -> transparentAddr.value = tfv
                            AddressBookContact.ADDR_TYPE_EVM -> evmAddr.value = tfv
                            AddressBookContact.ADDR_TYPE_SOLANA -> solanaAddr.value = tfv
                        }
                        showAdditionalAddresses.value = true
                        scanTargetField.value = null
                    } else {
                        walletAddress.value = TextFieldValue(addr)
                        error.value = null
                    }
                    onConsumeScannedWalletAddress()
                }
            }.launchIn(scope)

    fun close() {
        scanCollectorJob.cancel()
    }

    val state: StateFlow<EditChatContactState> =
        combine(
            combine(name, walletAddress) { n, w -> n to w },
            combine(transparentAddr, evmAddr, solanaAddr) { t, e, s -> Triple(t, e, s) },
            showAdditionalAddresses,
            showDeleteConfirm,
            error,
        ) { primary, extras, showExtras, showDelete, err ->
            val (nameVal, walletVal) = primary
            val (tAddr, eAddr, sAddr) = extras
            val originalWallet = contact.address.orEmpty()
            val hasChanges =
                nameVal.text.trim() != contact.name ||
                    walletVal.text.trim() != originalWallet ||
                    tAddr.text.trim() != initialTransparent ||
                    eAddr.text.trim() != initialEvm ||
                    sAddr.text.trim() != initialSolana
            val isSaveEnabled = (hasChanges || !isSaved) && nameVal.text.isNotBlank()
            EditChatContactState(
                publicKey = contact.publicKey,
                originalName = contact.name,
                originalWalletAddress = originalWallet,
                name = nameVal,
                walletAddress = walletVal,
                transparentAddr = tAddr,
                evmAddr = eAddr,
                solanaAddr = sAddr,
                showAdditionalAddresses = showExtras,
                showDeleteConfirm = showDelete,
                error = err,
                isSaveEnabled = isSaveEnabled,
                onNameChange = ::onNameChange,
                onWalletAddressChange = ::onWalletAddressChange,
                onTransparentAddrChange = ::onTransparentAddrChange,
                onEvmAddrChange = ::onEvmAddrChange,
                onSolanaAddrChange = ::onSolanaAddrChange,
                onToggleAdditionalAddresses = ::onToggleAdditionalAddresses,
                onScanWalletAddress = ::onScanPrimaryWalletAddress,
                onScanAddressField = ::onScanAddressField,
                onSave = ::onSave,
                onRequestDelete = ::onRequestDelete,
                onCancelDelete = ::onCancelDelete,
                onConfirmDelete = ::onConfirmDelete,
                onDismiss = onDismissRequest,
                onBlock = onBlock,
                isBlocked = contact.isBlocked,
                canDelete = isSaved,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = initialState(),
        )

    private fun initialState(): EditChatContactState {
        val originalWallet = contact.address.orEmpty()
        return EditChatContactState(
            publicKey = contact.publicKey,
            originalName = contact.name,
            originalWalletAddress = originalWallet,
            name = TextFieldValue(contact.name),
            walletAddress = TextFieldValue(originalWallet),
            transparentAddr = transparentAddr.value,
            evmAddr = evmAddr.value,
            solanaAddr = solanaAddr.value,
            showAdditionalAddresses = showAdditionalAddresses.value,
            showDeleteConfirm = false,
            error = null,
            isSaveEnabled = !isSaved && contact.name.isNotBlank(),
            onNameChange = ::onNameChange,
            onWalletAddressChange = ::onWalletAddressChange,
            onTransparentAddrChange = ::onTransparentAddrChange,
            onEvmAddrChange = ::onEvmAddrChange,
            onSolanaAddrChange = ::onSolanaAddrChange,
            onToggleAdditionalAddresses = ::onToggleAdditionalAddresses,
            onScanWalletAddress = ::onScanPrimaryWalletAddress,
            onScanAddressField = ::onScanAddressField,
            onSave = ::onSave,
            onRequestDelete = ::onRequestDelete,
            onCancelDelete = ::onCancelDelete,
            onConfirmDelete = ::onConfirmDelete,
            onDismiss = onDismissRequest,
            onBlock = onBlock,
            isBlocked = contact.isBlocked,
            canDelete = isSaved,
        )
    }

    private fun onNameChange(value: TextFieldValue) {
        name.value = value
        error.value = null
    }

    private fun onWalletAddressChange(value: TextFieldValue) {
        walletAddress.value = value
        error.value = null
    }

    private fun onTransparentAddrChange(value: TextFieldValue) {
        transparentAddr.value = value
    }

    private fun onEvmAddrChange(value: TextFieldValue) {
        evmAddr.value = value
    }

    private fun onSolanaAddrChange(value: TextFieldValue) {
        solanaAddr.value = value
    }

    private fun onToggleAdditionalAddresses() {
        showAdditionalAddresses.update { !it }
    }

    private fun onScanPrimaryWalletAddress() {
        scanTargetField.value = null
        onScanWalletAddressRequest()
    }

    private fun onScanAddressField(addrType: String) {
        scanTargetField.value = addrType
        onScanWalletAddressRequest()
    }

    private fun onSave() {
        val nameVal = name.value.text.trim()
        if (nameVal.isEmpty()) {
            error.value = stringRes(R.string.chat_contact_error_name_required)
            return
        }
        val addrs =
            buildMap {
                if (transparentAddr.value.text.isNotBlank()) {
                    put(AddressBookContact.ADDR_TYPE_TRANSPARENT, transparentAddr.value.text.trim())
                }
                if (evmAddr.value.text.isNotBlank()) {
                    put(AddressBookContact.ADDR_TYPE_EVM, evmAddr.value.text.trim())
                }
                if (solanaAddr.value.text.isNotBlank()) {
                    put(AddressBookContact.ADDR_TYPE_SOLANA, solanaAddr.value.text.trim())
                }
            }
        scope.launch {
            val wallet = walletAddress.value.text.trim()
            if (wallet.isNotEmpty() && !onValidateWalletAddress(wallet)) {
                error.value = stringRes(R.string.chat_contact_error_invalid_wallet_address)
                return@launch
            }
            if (onSaveContact(contact.publicKey, nameVal, wallet, addrs)) {
                onDismissRequest()
            } else {
                error.value = stringRes(R.string.chat_contact_error_save_failed)
            }
        }
    }

    private fun onRequestDelete() {
        showDeleteConfirm.value = true
    }

    private fun onCancelDelete() {
        showDeleteConfirm.value = false
    }

    private fun onConfirmDelete() {
        scope.launch {
            if (onDeleteContact(contact.publicKey)) {
                onDismissRequest()
            } else {
                showDeleteConfirm.value = false
                error.value = stringRes(R.string.chat_contact_error_delete_failed)
            }
        }
    }
}
