// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import co.electriccoin.zcash.ui.screen.gift.model.finalizing
import co.electriccoin.zcash.ui.screen.gift.model.markingClaimedElsewhere
import co.electriccoin.zcash.ui.screen.gift.model.recording
import co.electriccoin.zcash.ui.screen.gift.model.settling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * Receipts for the gifts this wallet has collected.
 *
 * Custody-critical **while a receipt is unsettled**. A claim that reached the mempool can still
 * expire unmined, and until finality the card can regain its funds and this record holds the
 * only link left that can move them — see [ReceivedGift]. Once settled it is ordinary history, and
 * losing it costs a row in a list rather than money.
 *
 * The store is strict so an older build cannot silently discard an unknown recovery field.
 */
interface ReceivedGiftStorageProvider {
    fun observe(): Flow<List<ReceivedGift>>

    suspend fun getAll(): List<ReceivedGift>

    /** Idempotent and monotonic per card. */
    suspend fun record(gift: ReceivedGift)

    /** True when any receipt still holds custody-critical retry material. */
    suspend fun hasUnsettledClaims(): Boolean = getAll().any { !it.isSettled }

    suspend fun markFinalized(address: String)

    /**
     * Drops the retained bearer secret for [address], its claim now being final.
     *
     * Only [ConfirmGiftClaimUseCase] should call this, and only on evidence: a receipt settled
     * early is a gift that cannot be retried if its claim never mines.
     */
    suspend fun settle(address: String)

    /** Records that another holder emptied this card, so re-opening the link need not rescan. */
    suspend fun markClaimedElsewhere(address: String)
}

internal class ReceivedGiftStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : ReceivedGiftStorageProvider {
    private val store =
        EncryptedJsonStore(
            encryptedPreferenceProvider,
            PREF_KEY,
            ListSerializer(ReceivedGift.serializer()),
            strict = true,
        )

    private val mutex = Mutex()

    override fun observe(): Flow<List<ReceivedGift>> = store.observe().map { it.orEmpty() }

    override suspend fun getAll(): List<ReceivedGift> = store.get().orEmpty()

    override suspend fun record(gift: ReceivedGift) =
        mutex.withLock { store.set(store.get().orEmpty().recording(gift)) }

    override suspend fun settle(address: String) =
        mutex.withLock { store.set(store.get().orEmpty().settling(address)) }

    override suspend fun markFinalized(address: String) =
        mutex.withLock { store.set(store.get().orEmpty().finalizing(address)) }

    override suspend fun markClaimedElsewhere(address: String) =
        mutex.withLock { store.set(store.get().orEmpty().markingClaimedElsewhere(address)) }

    private companion object {
        /** Never bump this without a migration: an unsettled receipt behind a dead key is money. */
        const val PREF_KEY = "received_gifts_v1"
    }
}
