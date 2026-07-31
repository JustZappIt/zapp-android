package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.spackle.io.deleteSuspend
import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.provider.AddressBookProvider
import co.electriccoin.zcash.ui.common.provider.AddressBookStorageProvider
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.addressbook.UnknownAddressBookVersionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

interface AddressBookDataSource {
    fun observe(key: AddressBookKey): Flow<AddressBook?>

    suspend fun saveContact(
        name: String,
        address: String,
        chain: String?,
        walletAddresses: Map<String, String>,
        key: AddressBookKey
    )

    suspend fun updateContact(
        contact: AddressBookContact,
        name: String,
        address: String,
        chain: String?,
        walletAddresses: Map<String, String>,
        key: AddressBookKey
    )

    suspend fun deleteContact(addressBookContact: AddressBookContact, key: AddressBookKey)

    suspend fun upsertContactByPublicKey(
        publicKey: String,
        name: String,
        address: String,
        walletAddresses: Map<String, String>,
        key: AddressBookKey
    )

    suspend fun setBlockedByPublicKey(
        publicKey: String,
        name: String,
        isBlocked: Boolean,
        key: AddressBookKey
    )

    suspend fun deleteContactByPublicKey(publicKey: String, key: AddressBookKey)

    suspend fun delete(key: AddressBookKey)
}

@Suppress("TooManyFunctions")
class AddressBookDataSourceImpl(
    private val addressBookStorageProvider: AddressBookStorageProvider,
    private val addressBookProvider: AddressBookProvider
) : AddressBookDataSource {
    private val mutex = Mutex()

    private val abUpdatePipeline = MutableSharedFlow<Pair<AddressBookKey, AddressBook?>>()

    override fun observe(key: AddressBookKey) =
        flow {
            emit(null)
            mutex.withLock { emit(getAddressBookInternal(key)) }
            abUpdatePipeline.collect { (newKey, newAddressBook) ->
                if (key.key.equalsSecretBytes(newKey.key)) {
                    emit(newAddressBook)
                }
            }
        }.distinctUntilChanged()

    override suspend fun saveContact(
        name: String,
        address: String,
        chain: String?,
        walletAddresses: Map<String, String>,
        key: AddressBookKey
    ) = updateAB(key) { contacts ->
        contacts +
            AddressBookContact(
                name = name.trim(),
                address = address.trim(),
                chain = chain?.trim()?.takeIf { it.isNotEmpty() },
                lastUpdated = getTimestampNow(),
                walletAddresses = walletAddresses.filterValues { it.isNotBlank() },
            )
    }

    override suspend fun updateContact(
        contact: AddressBookContact,
        name: String,
        address: String,
        chain: String?,
        walletAddresses: Map<String, String>,
        key: AddressBookKey
    ) = updateAB(key) { contacts ->
        contacts.apply {
            val index = indexOf(contact)
            // copy() so fields this editor doesn't know about (messagingPublicKey, isBlocked)
            // survive a wallet-side edit of a chat-linked contact.
            set(
                index,
                this[index].copy(
                    name = name.trim(),
                    address = address.trim(),
                    chain = chain?.trim()?.takeIf { it.isNotEmpty() },
                    lastUpdated = getTimestampNow(),
                    walletAddresses = walletAddresses.filterValues { it.isNotBlank() },
                )
            )
        }
    }

    override suspend fun deleteContact(addressBookContact: AddressBookContact, key: AddressBookKey) =
        updateAB(key) { it.apply { remove(addressBookContact) } }

    override suspend fun upsertContactByPublicKey(
        publicKey: String,
        name: String,
        address: String,
        walletAddresses: Map<String, String>,
        key: AddressBookKey
    ) = updateAB(key) { contacts ->
        val index = contacts.indexOfFirst { it.messagingPublicKey == publicKey }
        val filtered = walletAddresses.filterValues { it.isNotBlank() }
        if (index >= 0) {
            contacts.apply {
                set(
                    index,
                    this[index].copy(
                        name = name.trim(),
                        address = address.trim(),
                        walletAddresses = filtered,
                        lastUpdated = getTimestampNow(),
                    )
                )
            }
        } else {
            contacts +
                AddressBookContact(
                    name = name.trim(),
                    address = address.trim(),
                    chain = null,
                    lastUpdated = getTimestampNow(),
                    walletAddresses = filtered,
                    messagingPublicKey = publicKey,
                )
        }
    }

    override suspend fun setBlockedByPublicKey(
        publicKey: String,
        name: String,
        isBlocked: Boolean,
        key: AddressBookKey
    ) = updateAB(key) { contacts ->
        val index = contacts.indexOfFirst { it.messagingPublicKey == publicKey }
        val existing = contacts.getOrNull(index)
        when {
            existing == null -> {
                if (isBlocked) {
                    contacts +
                        AddressBookContact(
                            name = name.trim(),
                            address = "",
                            chain = null,
                            lastUpdated = getTimestampNow(),
                            messagingPublicKey = publicKey,
                            isBlocked = true,
                        )
                } else {
                    contacts
                }
            }

            existing.isBlocked == isBlocked -> {
                contacts
            }

            else -> {
                contacts.apply {
                    set(index, this[index].copy(isBlocked = isBlocked, lastUpdated = getTimestampNow()))
                }
            }
        }
    }

    override suspend fun deleteContactByPublicKey(publicKey: String, key: AddressBookKey) =
        updateAB(key) { contacts -> contacts.filterNot { it.messagingPublicKey == publicKey } }

    override suspend fun delete(key: AddressBookKey) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                addressBookStorageProvider.getStorageFile(key)?.delete()
                abUpdatePipeline.emit(key to null)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun getAddressBookInternal(addressBookKey: AddressBookKey): AddressBook {
        suspend fun readLocalFileToAddressBook(addressBookKey: AddressBookKey): AddressBook? {
            val encryptedFile = runCatching { addressBookStorageProvider.getStorageFile(addressBookKey) }.getOrNull()
            val unencryptedFile =
                runCatching { addressBookStorageProvider.getLegacyUnencryptedStorageFile() }.getOrNull()

            return when {
                encryptedFile != null -> {
                    // An unknown version header is deterministically unreadable (a stale file from
                    // a different format generation), so fall through to recreating an empty book.
                    // Everything else (decrypt/IO) may be transient and propagates instead of
                    // returning null — a null here makes the caller overwrite the file with an
                    // empty book, destroying every contact, chat link and block flag. Callers
                    // retry (repository retryWhen) or surface a Result failure.
                    runCatching {
                        addressBookProvider
                            .readAddressBookFromFile(encryptedFile, addressBookKey)
                            .also { unencryptedFile?.deleteSuspend() }
                    }.onFailure { e -> Twig.warn(e) { "Failed to read address book" } }
                        .recoverCatching { e ->
                            if (e is UnknownAddressBookVersionException) null else throw e
                        }.getOrThrow()
                }

                unencryptedFile != null -> {
                    addressBookProvider
                        .readLegacyUnencryptedAddressBookFromFile(unencryptedFile)
                        .also { unencryptedAddressBook ->
                            writeToLocalStorage(unencryptedAddressBook, addressBookKey)
                            unencryptedFile.deleteSuspend()
                        }
                }

                else -> {
                    null
                }
            }
        }

        return withContext(Dispatchers.IO) {
            var addressBook = readLocalFileToAddressBook(addressBookKey)
            if (addressBook == null) {
                addressBook = AddressBook(lastUpdated = getTimestampNow(), contacts = emptyList())
                writeToLocalStorage(addressBook, addressBookKey)
            }
            addressBook
        }
    }

    private fun writeToLocalStorage(addressBook: AddressBook, key: AddressBookKey) {
        runCatching { writeToLocalStorageOrThrow(addressBook, key) }
            .onFailure { e -> Twig.warn(e) { "Failed to write address book" } }
    }

    private fun writeToLocalStorageOrThrow(addressBook: AddressBook, key: AddressBookKey) {
        val file = addressBookStorageProvider.getOrCreateStorageFile(key)
        addressBookProvider.writeAddressBookToFile(file, addressBook, key)
    }

    private suspend fun updateAB(
        key: AddressBookKey,
        transform: (MutableList<AddressBookContact>) -> List<AddressBookContact>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val addressBook = getAddressBookInternal(key)
            val newContacts = transform(addressBook.contacts.toMutableList()).toList()
            // No-op transforms (e.g. unblocking a key with no row) skip the full re-encrypt/rewrite.
            if (newContacts == addressBook.contacts) return@withLock
            val newAddressBook =
                AddressBook(
                    lastUpdated = getTimestampNow(),
                    contacts = newContacts,
                )
            // Write failures must propagate (not just log) so callers don't report success — and
            // observers don't see — a state that was never persisted.
            writeToLocalStorageOrThrow(newAddressBook, key)
            abUpdatePipeline.emit(key to newAddressBook)
        }
    }

    private fun getTimestampNow(): Instant =
        Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
}
