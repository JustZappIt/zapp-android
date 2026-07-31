// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.repository

import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.model.normalizeMessagingPublicKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import xyz.justzappit.zappmessaging.ZappMessagingSDK

/**
 * Single source of truth for chat contacts. The encrypted address book owns a contact's name,
 * wallet addresses, and blocked flag (keyed by its messaging public key); the messaging SDK is a
 * pure registry of which public keys can be messaged. Every chat screen observes and writes through
 * this repository, so there is no by-address guessing, no dual store, and no manual refresh.
 */
interface ChatContactsRepository {
    /** Saved chat contacts (address-book rows carrying a messaging public key). */
    val contacts: StateFlow<List<ChatContact>>

    /** Public keys of blocked contacts, for filtering inbound messages. */
    val blockedKeys: StateFlow<Set<String>>

    /**
     * Suspends until the address book has actually loaded, unlike reading [contacts] directly,
     * whose backing state is seeded empty before the first load — a cold-start read there would
     * misreport a saved contact as unknown.
     */
    suspend fun getByPublicKey(publicKey: String): ChatContact?

    fun isBlocked(publicKey: String): Boolean

    suspend fun saveContact(
        publicKey: String,
        name: String,
        address: String,
        walletAddresses: Map<String, String>,
    ): Result<Unit>

    suspend fun deleteContact(publicKey: String): Result<Unit>

    suspend fun setBlocked(
        publicKey: String,
        name: String,
        isBlocked: Boolean,
    ): Result<Unit>
}

class ChatContactsRepositoryImpl(
    private val sdk: ZappMessagingSDK,
    private val addressBookRepository: AddressBookRepository,
    private val blockedKeysStorage: ChatBlockedKeysStorageProvider,
) : ChatContactsRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override val contacts: StateFlow<List<ChatContact>> =
        addressBookRepository.contacts
            .map { rows -> rows.orEmpty().mapNotNull { it.toChatContact() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // The address book is authoritative, but it is seed-derived and only loads once the wallet
    // account resolves — which never happens in the cold, headless ChatWakeService. Mirror the
    // blocked set into prefs (seeded synchronously at construction, refreshed only on real
    // address-book loads via filterNotNull) so the synchronous filter never fails open.
    override val blockedKeys: StateFlow<Set<String>> =
        addressBookRepository.contacts
            .filterNotNull()
            .map { rows ->
                rows
                    .filter { it.isBlocked }
                    .mapNotNull { row -> row.messagingPublicKey?.let(::normalizeKey) }
                    .toSet()
            }.onEach { blockedKeysStorage.store(it) }
            .stateIn(scope, SharingStarted.Eagerly, blockedKeysStorage.get())

    override suspend fun getByPublicKey(publicKey: String): ChatContact? {
        val key = normalizeKey(publicKey)
        return addressBookRepository.contacts
            .filterNotNull()
            .first()
            .mapNotNull { it.toChatContact() }
            .firstOrNull { it.publicKey == key }
    }

    override fun isBlocked(publicKey: String): Boolean = blockedKeys.value.contains(normalizeKey(publicKey))

    // Writes below run under NonCancellable: they are launched from viewModelScopes that a
    // navigate-back cancels mid-flight, which must not split the SDK/address-book two-phase
    // write or silently drop a confirmed block.

    override suspend fun saveContact(
        publicKey: String,
        name: String,
        address: String,
        walletAddresses: Map<String, String>,
    ): Result<Unit> =
        runChatCallResult("ChatContactsRepository: saveContact failed") {
            val key = normalizeKey(publicKey)
            require(key.isNotBlank()) { "messaging public key must not be blank" }
            withContext(NonCancellable) {
                // Mirror the name into the messaging registry so the peer stays messageable, then
                // write the source-of-truth address-book row keyed by the public key.
                if (sdk.contacts.value.any { normalizeKey(it.publicKey) == key }) {
                    sdk.updateContact(key, name)
                } else {
                    sdk.addContact(key, name)
                }
                addressBookRepository.upsertContactByPublicKey(key, name, address, walletAddresses)
            }
        }

    override suspend fun deleteContact(publicKey: String): Result<Unit> =
        runChatCallResult("ChatContactsRepository: deleteContact failed") {
            val key = normalizeKey(publicKey)
            val existing = contacts.value.firstOrNull { it.publicKey == key }
            withContext(NonCancellable) {
                sdk.deleteContact(key)
                addressBookRepository.deleteContactByPublicKey(key)
                // Deleting a blocked contact keeps the block as a details-less row; otherwise
                // cleaning up the contact list doubles as a silent unblock.
                if (existing?.isBlocked == true) {
                    addressBookRepository.setContactBlocked(key, existing.name, true)
                }
            }
        }

    override suspend fun setBlocked(
        publicKey: String,
        name: String,
        isBlocked: Boolean,
    ): Result<Unit> =
        runChatCallResult("ChatContactsRepository: setBlocked failed") {
            val key = normalizeKey(publicKey)
            withContext(NonCancellable) {
                // Saved contacts are always in the SDK registry, so a row absent from it exists
                // only because of the block: unblocking removes it, leaving no residual contact
                // after block-then-unblock of a stranger.
                val isSavedContact = sdk.contacts.value.any { normalizeKey(it.publicKey) == key }
                if (!isBlocked && !isSavedContact) {
                    addressBookRepository.deleteContactByPublicKey(key)
                } else {
                    addressBookRepository.setContactBlocked(key, name, isBlocked)
                }
            }
        }

    private fun EnhancedABContact.toChatContact(): ChatContact? {
        val key = messagingPublicKey ?: return null
        return ChatContact(
            publicKey = normalizeKey(key),
            name = name,
            address = address.takeIf { it.isNotBlank() },
            walletAddresses = walletAddresses,
            isBlocked = isBlocked,
        )
    }
}

// Keys are matched by equality in the address book, the SDK registry and the blocked set, while
// user-entered keys arrive 0x-prefixed and/or uppercase — normalize once at the boundary.
private fun normalizeKey(publicKey: String): String = publicKey.normalizeMessagingPublicKey()
