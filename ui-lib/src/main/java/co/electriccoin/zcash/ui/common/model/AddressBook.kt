package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_SERIALIZATION_ZAPP_V4
import kotlin.time.Instant

data class AddressBook(
    val lastUpdated: Instant,
    val version: Int = ADDRESS_BOOK_SERIALIZATION_ZAPP_V4,
    val contacts: List<AddressBookContact>
)
