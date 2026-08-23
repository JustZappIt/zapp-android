// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardLedger
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import co.electriccoin.zcash.ui.screen.gift.model.hasUnsharedFunds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * The local half of every gift card this wallet has minted.
 *
 * Custody-critical: for a card whose link has not been shared, this store is the only route back to
 * the funds. Excluded from Android Auto Backup by construction — the backup configs are allowlists
 * naming only `address_book`, so `domain="sharedpref"` is not backed up. Never add a `sharedpref`
 * include.
 *
 * One method per legal ledger transition; collapsing them into a generic mutator is what the
 * per-transition guards exist to prevent.
 */
@Suppress("TooManyFunctions")
interface GiftCardStorageProvider {
    fun observe(): Flow<List<StoredGiftCard>>

    suspend fun getAll(): List<StoredGiftCard>

    suspend fun get(id: String): StoredGiftCard?

    /** Persists a freshly minted card. Must complete before its funding transaction is submitted. */
    suspend fun add(card: StoredGiftCard)

    /** Flags funding before SDK transaction creation. Submitted/funded transitions clear it. */
    suspend fun setFundingAttemptedAt(id: String, at: String)

    /** Stores the txid created after the durable funding-start marker. */
    suspend fun recordFundingCreated(id: String, fundingTxid: String, at: String)

    /** Records a submitted funding txid. The card stays a draft until the transaction mines. */
    suspend fun recordFundingSubmitted(id: String, fundingTxid: String, at: String)

    suspend fun markFunded(id: String, fundingTxid: String, at: String)

    suspend fun markShared(id: String, at: String)

    /** Records that the card's own wallet was scanned and still held its funds. */
    suspend fun recordChecked(id: String, at: String)

    /** Records that the card's own wallet was observed empty, so its funds are settled. */
    suspend fun markClaimed(id: String, at: String)

    /**
     * True while [accountUuid] — or any account, when it is null — owns funded cards whose links
     * were never shared. Blocks deleting that account, and blocks the wallet wipe, which clears
     * this whole store.
     *
     * Stays a member rather than an extension so tests can substitute it.
     */
    suspend fun hasUnsharedFunds(accountUuid: String? = null): Boolean =
        hasUnsharedFunds(getAll(), accountUuid)
}

@Suppress("TooManyFunctions")
internal class GiftCardStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : GiftCardStorageProvider {
    // One key holding the whole list: EncryptedJsonStore is single-key, and a list makes each
    // mutation a single atomic write.
    //
    // Strict, unlike every other store on EncryptedJsonStore. A tolerant decode drops an unknown
    // field and writes the record back without it, which here means an older build silently
    // discarding part of the only copy of a card's recovery data. Failing the read is safe:
    // `mutate` reads before it writes, so a refused read refuses the write too.
    //
    // The corollary is that the key must NOT be versioned — bumping it reads back an absent key,
    // indistinguishable from "no cards", orphaning every stored seed behind a name nothing looks up
    // any more. Additive fields with defaults are the supported change; anything else needs a
    // migration that reads the old key and writes the new one before this constructor is reached.
    private val store =
        EncryptedJsonStore(
            encryptedPreferenceProvider,
            PREF_KEY,
            ListSerializer(StoredGiftCard.serializer()),
            strict = true,
        )

    // Every mutation is read-modify-write over the whole list, so concurrent ones would otherwise
    // interleave and lose a card.
    private val mutex = Mutex()

    override fun observe(): Flow<List<StoredGiftCard>> = store.observe().map { it.orEmpty() }

    override suspend fun getAll(): List<StoredGiftCard> = store.get().orEmpty()

    override suspend fun get(id: String): StoredGiftCard? = getAll().firstOrNull { it.id == id }

    override suspend fun add(card: StoredGiftCard) = mutate { GiftCardLedger.add(it, card) }

    override suspend fun setFundingAttemptedAt(id: String, at: String) =
        mutate { GiftCardLedger.setFundingAttemptedAt(it, id, at) }

    override suspend fun recordFundingCreated(id: String, fundingTxid: String, at: String) =
        mutate { GiftCardLedger.recordFundingCreated(it, id, fundingTxid, at) }

    override suspend fun recordFundingSubmitted(id: String, fundingTxid: String, at: String) =
        mutate { GiftCardLedger.recordFundingSubmitted(it, id, fundingTxid, at) }

    override suspend fun markFunded(id: String, fundingTxid: String, at: String) =
        mutate { GiftCardLedger.markFunded(it, id, fundingTxid, at) }

    override suspend fun markShared(id: String, at: String) = mutate { GiftCardLedger.markShared(it, id, at) }

    override suspend fun recordChecked(id: String, at: String) = mutate { GiftCardLedger.recordChecked(it, id, at) }

    override suspend fun markClaimed(id: String, at: String) = mutate { GiftCardLedger.markClaimed(it, id, at) }

    // store.get() throws StoreCorruptedException rather than returning null for a blob that is
    // present but will not decode, and that is deliberately not caught here: treating corrupt as
    // absent would let the next write replace a list of funded cards with a list of one.
    private suspend fun mutate(transform: (List<StoredGiftCard>) -> List<StoredGiftCard>) =
        mutex.withLock { store.set(transform(store.get().orEmpty())) }

    private companion object {
        /** Never bump this without a migration that carries the records across — see [store]. */
        const val PREF_KEY = "gift_cards_v1"
    }
}
