package co.electriccoin.zcash.ui.common.serialization.addressbook

import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_SERIALIZATION_V1
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_SERIALIZATION_V2
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_SERIALIZATION_ZAPP_V3
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_SERIALIZATION_ZAPP_V4
import co.electriccoin.zcash.ui.common.serialization.BaseSerializer
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant

/**
 * The file's version header is not one this build can read — a deterministic incompatibility
 * (stale fork-format file, foreign build), unlike a transient decrypt/IO failure.
 */
class UnknownAddressBookVersionException(
    version: Int
) : RuntimeException("Unknown address book version: $version")

class AddressBookSerializer : BaseSerializer() {
    fun serializeAddressBook(
        outputStream: OutputStream,
        addressBook: AddressBook
    ) {
        // The body below is always the ZAPP_V4 layout, so the header must not echo the in-memory
        // version of a book deserialized from an older file (legacy re-persist would otherwise
        // write an older header over a ZAPP_V4 body and corrupt on the next read).
        outputStream.write(ADDRESS_BOOK_SERIALIZATION_ZAPP_V4.createByteArray())
        outputStream.write(addressBook.lastUpdated.toEpochMilliseconds().createByteArray())
        outputStream.write(addressBook.contacts.size.createByteArray())

        addressBook.contacts.forEach { contact ->
            outputStream.write(contact.lastUpdated.toEpochMilliseconds().createByteArray())
            outputStream.write(contact.address.createByteArray())
            outputStream.write(contact.name.createByteArray())
            outputStream.write(contact.chain.createByteArray())
            outputStream.write(contact.walletAddresses.size.createByteArray())
            contact.walletAddresses.forEach { (key, value) ->
                outputStream.write(key.createByteArray())
                outputStream.write(value.createByteArray())
            }
            outputStream.write(contact.messagingPublicKey.createByteArray())
            outputStream.write((if (contact.isBlocked) 1 else 0).createByteArray())
        }
    }

    fun deserializeAddressBook(inputStream: InputStream): AddressBook =
        when (val version = inputStream.readInt()) {
            ADDRESS_BOOK_SERIALIZATION_V1 -> {
                AddressBook(
                    version = ADDRESS_BOOK_SERIALIZATION_V1,
                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                    contacts =
                        inputStream.readInt().let { contactsSize ->
                            (0 until contactsSize).map { _ ->
                                AddressBookContact(
                                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                                    address = inputStream.readString(),
                                    name = inputStream.readString(),
                                    chain = null,
                                )
                            }
                        }
                )
            }

            ADDRESS_BOOK_SERIALIZATION_V2 -> {
                AddressBook(
                    version = ADDRESS_BOOK_SERIALIZATION_V2,
                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                    contacts =
                        inputStream.readInt().let { contactsSize ->
                            (0 until contactsSize).map { _ ->
                                AddressBookContact(
                                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                                    address = inputStream.readString(),
                                    name = inputStream.readString(),
                                    chain = inputStream.readString().takeIf { it.isNotEmpty() },
                                )
                            }
                        }
                )
            }

            ADDRESS_BOOK_SERIALIZATION_ZAPP_V3 -> {
                AddressBook(
                    version = ADDRESS_BOOK_SERIALIZATION_ZAPP_V3,
                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                    contacts =
                        inputStream.readInt().let { contactsSize ->
                            (0 until contactsSize).map { _ ->
                                AddressBookContact(
                                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                                    address = inputStream.readString(),
                                    name = inputStream.readString(),
                                    chain = inputStream.readString().takeIf { it.isNotEmpty() },
                                    walletAddresses =
                                        inputStream.readInt().let { mapSize ->
                                            (0 until mapSize).associate { _ ->
                                                inputStream.readString() to inputStream.readString()
                                            }
                                        },
                                )
                            }
                        }
                )
            }

            ADDRESS_BOOK_SERIALIZATION_ZAPP_V4 -> {
                AddressBook(
                    version = ADDRESS_BOOK_SERIALIZATION_ZAPP_V4,
                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                    contacts =
                        inputStream.readInt().let { contactsSize ->
                            (0 until contactsSize).map { _ ->
                                AddressBookContact(
                                    lastUpdated = inputStream.readLong().let { Instant.fromEpochMilliseconds(it) },
                                    address = inputStream.readString(),
                                    name = inputStream.readString(),
                                    chain = inputStream.readString().takeIf { it.isNotEmpty() },
                                    walletAddresses =
                                        inputStream.readInt().let { mapSize ->
                                            (0 until mapSize).associate { _ ->
                                                inputStream.readString() to inputStream.readString()
                                            }
                                        },
                                    messagingPublicKey = inputStream.readString().takeIf { it.isNotEmpty() },
                                    isBlocked = inputStream.readInt() == 1,
                                )
                            }
                        }
                )
            }

            else -> {
                throw UnknownAddressBookVersionException(version)
            }
        }
}
