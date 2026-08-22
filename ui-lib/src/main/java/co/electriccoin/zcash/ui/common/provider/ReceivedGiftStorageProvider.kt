// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import co.electriccoin.zcash.ui.screen.gift.model.recording
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * Receipts for the gifts this wallet has collected.
 *
 * Unlike `GiftCardStorageProvider` this is **not** custody-critical: a claimed gift's funds are
 * ordinary wallet funds recoverable from the seed phrase, and nothing here holds key material. It
 * exists so a claim is more than an anonymous incoming transaction — losing it loses history, not
 * money, which is why nothing guards its deletion.
 */
interface ReceivedGiftStorageProvider {
    fun observe(): Flow<List<ReceivedGift>>

    suspend fun getAll(): List<ReceivedGift>

    /** Idempotent per card: re-opening the same link replaces its receipt rather than adding one. */
    suspend fun record(gift: ReceivedGift)
}

internal class ReceivedGiftStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : ReceivedGiftStorageProvider {
    private val store =
        EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, ListSerializer(ReceivedGift.serializer()))

    private val mutex = Mutex()

    override fun observe(): Flow<List<ReceivedGift>> = store.observe().map { it.orEmpty() }

    override suspend fun getAll(): List<ReceivedGift> = store.get().orEmpty()

    override suspend fun record(gift: ReceivedGift) =
        mutex.withLock { store.set(store.get().orEmpty().recording(gift)) }

    private companion object {
        const val PREF_KEY = "received_gifts_v1"
    }
}
