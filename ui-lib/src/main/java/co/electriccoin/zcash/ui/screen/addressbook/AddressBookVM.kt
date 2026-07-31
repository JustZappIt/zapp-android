package co.electriccoin.zcash.ui.screen.addressbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.usecase.GetABContactsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanPublicKeyUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.listitem.ContactListItemState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddressBookVM(
    getAddressBookContacts: GetABContactsUseCase,
    private val navigationRouter: NavigationRouter,
    private val navigateToScanGenericAddressUseCase: NavigateToScanGenericAddressUseCase,
    private val navigateToScanPublicKeyUseCase: NavigateToScanPublicKeyUseCase,
    private val addressBookRepository: AddressBookRepository,
    private val chatContactsRepository: ChatContactsRepository,
) : ViewModel() {
    private val scannedAddress = MutableStateFlow<String?>(null)
    private val scannedMessagingKey = MutableStateFlow<String?>(null)
    private val editingContact = MutableStateFlow<EnhancedABContact?>(null)

    val state =
        combine(
            getAddressBookContacts.observe(zcashContactsOnly = false),
            scannedAddress,
            scannedMessagingKey,
            editingContact,
        ) { contacts, scannedAddr, scannedKey, editing ->
            createState(
                contacts = contacts,
                scannedAddress = scannedAddr,
                scannedMessagingKey = scannedKey,
                editingContact = editing,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    createState(
                        contacts = null,
                        scannedAddress = null,
                        scannedMessagingKey = null,
                        editingContact = null,
                    )
            )

    private fun createState(
        contacts: List<EnhancedABContact>?,
        scannedAddress: String?,
        scannedMessagingKey: String?,
        editingContact: EnhancedABContact?,
    ) =
        AddressBookState(
            isLoading = contacts == null,
            items =
                contacts
                    ?.map { contact ->
                        AddressBookItem.Contact(
                            ContactListItemState(
                                bigIcon = getContactInitials(contact),
                                smallIcon = contact.blockchain?.chainIcon,
                                isShielded = false,
                                name = stringRes(contact.name),
                                address = stringResByAddress(value = contact.address),
                                onClick = { onContactClick(contact) },
                            )
                        )
                    }.orEmpty(),
            onBack = ::onBack,
            manualButton =
                ButtonState(
                    onClick = {},
                    text = stringRes(R.string.address_book_manual_btn)
                ),
            scanButton =
                ButtonState(
                    onClick = {},
                    text = stringRes(R.string.address_book_scan_btn)
                ),
            title = stringRes(R.string.address_book_title),
            info = null,
            onSaveNewContact = ::onSaveNewContact,
            onScanMessagingKey = ::onScanMessagingKey,
            scannedMessagingKey = scannedMessagingKey,
            onConsumeScannedMessagingKey = ::onConsumeScannedMessagingKey,
            onScanQr = ::onScanQr,
            scannedAddress = scannedAddress,
            onConsumeScannedAddress = ::onConsumeScannedAddress,
            editingContact =
                editingContact?.let {
                    EditContactData(
                        originalName = it.name,
                        originalAddress = it.address,
                        messagingKey = null,
                        walletAddresses = it.walletAddresses,
                    )
                },
            onUpdateContact = ::onUpdateContact,
            onDeleteContact = ::onDeleteContact,
            onDismissEdit = ::onDismissEdit,
        )

    private fun getContactInitials(contact: EnhancedABContact) =
        imageRes(
            contact.name
                .split(" ")
                .mapNotNull { part ->
                    part.takeIf { it.isNotEmpty() }?.first()?.toString()
                }.take(2)
                .joinToString(separator = "")
        )

    private fun onBack() = navigationRouter.back()

    private fun onContactClick(contact: EnhancedABContact) {
        editingContact.update { contact }
    }

    private fun onDismissEdit() {
        editingContact.update { null }
    }

    private fun onUpdateContact(name: String, walletAddress: String, walletAddresses: Map<String, String>) {
        val contact = editingContact.value ?: return
        addressBookRepository.updateContact(
            contact = contact,
            name = name,
            address = walletAddress,
            chain = contact.blockchain?.chainTicker,
            walletAddresses = walletAddresses,
        )
        editingContact.update { null }
    }

    private fun onDeleteContact() {
        val contact = editingContact.value ?: return
        addressBookRepository.deleteContact(contact)
        editingContact.update { null }
    }

    private fun onSaveNewContact(name: String, messagingKey: String, walletAddress: String, walletAddresses: Map<String, String>) {
        if (messagingKey.isNotEmpty()) {
            // A contact with a messaging key is a chat contact: one linked row through the chat
            // repository, not an address-book row plus a disconnected SDK registry entry.
            viewModelScope.launch {
                chatContactsRepository.saveContact(
                    publicKey = messagingKey,
                    name = name,
                    address = walletAddress,
                    walletAddresses = walletAddresses,
                )
            }
        } else if (walletAddress.isNotEmpty() || walletAddresses.isNotEmpty()) {
            addressBookRepository.saveContact(
                name = name,
                address = walletAddress,
                chain = null,
                walletAddresses = walletAddresses,
            )
        }
    }

    private fun onScanMessagingKey() =
        viewModelScope.launch {
            val key = navigateToScanPublicKeyUseCase()
            if (key != null) {
                scannedMessagingKey.update { key }
            }
        }

    private fun onConsumeScannedMessagingKey() {
        scannedMessagingKey.update { null }
    }

    private fun onScanQr() =
        viewModelScope.launch {
            val result = navigateToScanGenericAddressUseCase()
            if (result != null) {
                scannedAddress.update { result.address }
            }
        }

    private fun onConsumeScannedAddress() {
        scannedAddress.update { null }
    }
}

internal const val ADDRESS_MAX_LENGTH = 20
