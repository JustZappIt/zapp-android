// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardLedger
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * The local half of every gift card this wallet has minted.
 *
 * Custody-critical. The ephemeral seed is random rather than derived from the wallet seed and there
 * is no reclaim, so for a card whose link has not been shared this store is the only route back to
 * the funds. It is also excluded from Android Auto Backup by construction — the backup configs are
 * allowlists naming only `address_book`, so `domain="sharedpref"` is not backed up. Never add a
 * `sharedpref` include.
 */
interface GiftCardStorageProvider {
    fun observe(): Flow<List<StoredGiftCard>>

    suspend fun getAll(): List<StoredGiftCard>

    suspend fun get(id: String): StoredGiftCard?

    /** Persists a freshly minted card. Must complete before its funding transaction is submitted. */
    suspend fun add(card: StoredGiftCard)

    /** Flags a broadcast as in flight, or clears the flag with a null [at] once it is resolved. */
    suspend fun setFundingAttemptedAt(id: String, at: String?)

    /** Records a submitted funding txid. The card stays a draft until the transaction mines. */
    suspend fun recordFundingSubmitted(id: String, fundingTxid: String, at: String)

    suspend fun markFunded(id: String, fundingTxid: String, at: String)

    suspend fun markShared(id: String, at: String)

    suspend fun archive(id: String, at: String)

    /**
     * True while [accountUuid] — or any account, when it is null — owns funded cards whose links
     * were never shared. Blocks deleting that account, and blocks the wallet wipe, which clears
     * this whole store.
     */
    suspend fun hasUnsharedFunds(accountUuid: String? = null): Boolean =
        GiftCardLedger.hasUnsharedFunds(getAll(), accountUuid)
}

internal class GiftCardStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : GiftCardStorageProvider {
    // One versioned key holding the whole list, rather than a key per card: EncryptedJsonStore is
    // single-key, and a list makes each mutation a single atomic write. Schema changes bump the
    // key; they never loosen the decode, because a decode that shrugs at a field it does not
    // recognise is a decode that can quietly drop a card.
    private val store =
        EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, ListSerializer(StoredGiftCard.serializer()))

    // Every mutation is read-modify-write over the whole list, so concurrent ones would otherwise
    // interleave and lose a card.
    private val mutex = Mutex()

    override fun observe(): Flow<List<StoredGiftCard>> = store.observe().map { it.orEmpty() }

    override suspend fun getAll(): List<StoredGiftCard> = store.get().orEmpty()

    override suspend fun get(id: String): StoredGiftCard? = getAll().firstOrNull { it.id == id }

    override suspend fun add(card: StoredGiftCard) = mutate { GiftCardLedger.add(it, card) }

    override suspend fun setFundingAttemptedAt(id: String, at: String?) =
        mutate { GiftCardLedger.setFundingAttemptedAt(it, id, at) }

    override suspend fun recordFundingSubmitted(id: String, fundingTxid: String, at: String) =
        mutate { GiftCardLedger.recordFundingSubmitted(it, id, fundingTxid, at) }

    override suspend fun markFunded(id: String, fundingTxid: String, at: String) =
        mutate { GiftCardLedger.markFunded(it, id, fundingTxid, at) }

    override suspend fun markShared(id: String, at: String) = mutate { GiftCardLedger.markShared(it, id, at) }

    override suspend fun archive(id: String, at: String) = mutate { GiftCardLedger.archive(it, id, at) }

    // store.get() throws StoreCorruptedException rather than returning null for a blob that is
    // present but will not decode, and that is deliberately not caught here: treating corrupt as
    // absent would let the next write replace a list of funded cards with a list of one.
    private suspend fun mutate(transform: (List<StoredGiftCard>) -> List<StoredGiftCard>) =
        mutex.withLock { store.set(transform(store.get().orEmpty())) }

    private companion object {
        const val PREF_KEY = "gift_cards_v1"
    }
}
