// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanPublicKeyUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import co.electriccoin.zcash.ui.screen.chat.view.BlockUserDialogState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ConversationType as SdkConversationType

@Suppress("TooManyFunctions")
class ChatContactsVM(
    private val sdk: ZappMessagingSDK,
    private val chatContactsRepository: ChatContactsRepository,
    private val navigateToScanPublicKey: NavigateToScanPublicKeyUseCase,
    private val navigateToScanGenericAddress: NavigateToScanGenericAddressUseCase,
    private val validateAddress: ValidateAddressUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val scannedPublicKey = MutableStateFlow<String?>(null)
    private val scannedWalletAddress = MutableStateFlow<String?>(null)
    private val blockDialog = MutableStateFlow<BlockUserDialogState?>(null)

    // Per-sheet VMs. The parent owns these because the sheets share its scan
    // bridge and contact list — see AddChatContactVM / EditChatContactVM kdoc.
    private val addSheet = MutableStateFlow<AddChatContactVM?>(null)
    private val editSheet = MutableStateFlow<EditChatContactVM?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val addSheetState: StateFlow<AddChatContactState?> =
        addSheet
            .flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val editSheetState: StateFlow<EditChatContactState?> =
        editSheet
            .flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    val state: StateFlow<ChatContactsState> =
        combine(
            chatContactsRepository.contacts,
            addSheetState,
            editSheetState,
            blockDialog,
        ) { list, add, edit, block ->
            createState(list, add, edit, block)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(emptyList(), null, null, null),
        )

    private fun createState(
        list: List<ChatContact>,
        add: AddChatContactState?,
        edit: EditChatContactState?,
        block: BlockUserDialogState?,
    ): ChatContactsState =
        ChatContactsState(
            title = stringRes(R.string.chat_contacts_title),
            contacts = list,
            onStartChat = ::onStartChat,
            onAddSheetOpen = ::openAddSheet,
            onEditSheetOpen = ::openEditSheet,
            onBack = ::onBack,
            addSheet = add,
            editSheet = edit,
            blockDialog = block,
        )

    private fun onBack() = navigationRouter.back()

    private fun onStartChat(publicKey: String) {
        viewModelScope.launch { createDirectChat(publicKey) }
    }

    private suspend fun createDirectChat(publicKey: String) {
        val cleaned = publicKey.trim().removePrefix("0x")
        runChatCall("ChatContactsVM: createConversation failed") {
            val conv =
                sdk.createConversation(
                    type = SdkConversationType.DIRECT,
                    participants = listOf(cleaned),
                    displayName = null,
                )
            navigationRouter.forward(ChatRoomArgs(conv.id))
        }
    }

    private fun openAddSheet() {
        if (addSheet.value != null) return
        addSheet.value =
            AddChatContactVM(
                scope = viewModelScope,
                existingKeysProvider = {
                    chatContactsRepository.contacts.value
                        .map { it.publicKey }
                        .toSet()
                },
                scannedPublicKeyFlow = scannedPublicKey.asStateFlow(),
                scannedWalletAddressFlow = scannedWalletAddress.asStateFlow(),
                onConsumeScannedPublicKey = ::consumeScannedPublicKey,
                onConsumeScannedWalletAddress = ::consumeScannedWalletAddress,
                onScanPublicKeyRequest = ::onScanPublicKey,
                onScanWalletAddressRequest = ::onScanWalletAddress,
                onSaveContact = ::addContactFromSheet,
                onValidateWalletAddress = ::isValidZcashAddress,
                onDismissRequest = ::closeAddSheet,
            )
    }

    private fun closeAddSheet() {
        addSheet.value?.close()
        addSheet.value = null
    }

    private fun openEditSheet(contact: ChatContact) {
        if (editSheet.value != null) return
        // The reactive contact already carries name, addresses and blocked state, so the sheet seeds
        // synchronously — no address lookup, no bridge var, no open/close race.
        editSheet.value =
            EditChatContactVM(
                contact = contact,
                scope = viewModelScope,
                scannedWalletAddressFlow = scannedWalletAddress.asStateFlow(),
                onConsumeScannedWalletAddress = ::consumeScannedWalletAddress,
                onScanWalletAddressRequest = ::onScanWalletAddress,
                onSaveContact = ::updateContactFromSheet,
                onDeleteContact = ::deleteContactFromSheet,
                onValidateWalletAddress = ::isValidZcashAddress,
                onDismissRequest = ::closeEditSheet,
                onBlock = { onEditContactBlock(contact) },
                initialWalletAddresses = contact.walletAddresses,
            )
    }

    private fun closeEditSheet() {
        editSheet.value?.close()
        editSheet.value = null
    }

    private fun onScanPublicKey() {
        viewModelScope.launch {
            val key = navigateToScanPublicKey()
            if (!key.isNullOrBlank()) scannedPublicKey.value = key
        }
    }

    private fun onScanWalletAddress() {
        viewModelScope.launch {
            val result = navigateToScanGenericAddress()
            if (result != null) scannedWalletAddress.value = result.address
        }
    }

    private fun consumeScannedPublicKey() {
        scannedPublicKey.value = null
    }

    private fun consumeScannedWalletAddress() {
        scannedWalletAddress.value = null
    }

    private suspend fun addContactFromSheet(
        publicKey: String,
        name: String,
        walletAddress: String,
        walletAddresses: Map<String, String>,
    ): Boolean {
        val result = chatContactsRepository.saveContact(publicKey, name, walletAddress.trim(), walletAddresses)
        if (result.isSuccess) {
            consumeScannedPublicKey()
            consumeScannedWalletAddress()
            closeAddSheet()
        }
        return result.isSuccess
    }

    private suspend fun updateContactFromSheet(
        publicKey: String,
        name: String,
        walletAddress: String,
        walletAddresses: Map<String, String>,
    ): Boolean = chatContactsRepository.saveContact(publicKey, name, walletAddress, walletAddresses).isSuccess

    private suspend fun deleteContactFromSheet(publicKey: String): Boolean =
        chatContactsRepository.deleteContact(publicKey).isSuccess

    private suspend fun isValidZcashAddress(address: String): Boolean = !validateAddress(address).isNotValid

    private fun onEditContactBlock(contact: ChatContact) {
        closeEditSheet()
        blockDialog.value =
            BlockUserDialogState(
                displayName = contact.name,
                isUnblock = contact.isBlocked,
                onConfirm = { onBlockConfirm(contact.publicKey, contact.name, !contact.isBlocked) },
                onDismiss = ::dismissBlockDialog,
            )
    }

    private fun onBlockConfirm(
        publicKey: String,
        displayName: String,
        isBlocked: Boolean,
    ) {
        // The dialog only dismisses on success; a failed write keeps it up so the user can retry
        // instead of walking away believing the block/unblock took effect.
        viewModelScope.launch {
            if (chatContactsRepository.setBlocked(publicKey, displayName, isBlocked).isSuccess) {
                blockDialog.value = null
            }
        }
    }

    private fun dismissBlockDialog() {
        blockDialog.value = null
    }
}
