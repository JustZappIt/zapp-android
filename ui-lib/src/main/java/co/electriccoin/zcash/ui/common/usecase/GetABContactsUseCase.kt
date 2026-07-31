package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import kotlinx.coroutines.flow.map

class GetABContactsUseCase(
    private val addressBookRepository: AddressBookRepository
) {
    fun observe(zcashContactsOnly: Boolean) =
        addressBookRepository
            .contacts
            .map { contacts ->
                contacts
                    // Chat-only and block-only rows carry no wallet address; they belong to the
                    // chat surfaces, not the wallet address book or the send-recipient picker.
                    ?.filter { it.address.isNotBlank() || it.walletAddresses.isNotEmpty() }
                    ?.filter { contact -> if (zcashContactsOnly) contact.blockchain == null else true }
            }
}
