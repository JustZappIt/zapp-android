// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapStore

/** The reverse swaps kept on this device, as the app follows them. */
interface ReverseSwapRecords : ReverseSwapStore {
    val observeActive: Flow<ReverseSwapRecord?>

    /** The conversions the user went ahead with, oldest first; a quote only previewed is never kept. */
    val observeHistory: Flow<List<ReverseSwapRecord>>

    /** The conversion under way, if any. One the store can't read can't be carried on either, so it is none. */
    suspend fun underWay(): ReverseSwapRecord?

    /** Forgets every conversion, for a wallet about to be wiped. */
    suspend fun clear()
}

class ReverseSwapStoreImpl(
    preferences: EncryptedPreferenceProvider
) : ReverseSwapRecords {
    private val store = EncryptedJsonStore(preferences, "reverse_swap_v2", State.serializer(), strict = true)
    private val lock = Mutex()

    override val observeActive: Flow<ReverseSwapRecord?> = store.observe().map { it?.active }

    override val observeHistory: Flow<List<ReverseSwapRecord>> = store.observe().map { it?.history.orEmpty() }

    override suspend fun active(): ReverseSwapRecord? = store.get()?.active

    override suspend fun underWay(): ReverseSwapRecord? =
        try {
            active()?.takeIf { it.underWay }
        } catch (e: StoreCorruptedException) {
            Twig.error(e) { "Reverse swap: the store is unreadable" }
            null
        }

    override suspend fun find(index: Int): ReverseSwapRecord? =
        store.get()?.let { state ->
            state.active?.takeIf { it.index == index } ?: state.history.find { it.index == index }
        }

    override suspend fun save(record: ReverseSwapRecord) =
        lock.withLock {
            val earlier = (store.get() ?: State()).history.filterNot { it.index == record.index }
            val all = earlier + listOfNotNull(record.takeUnless { it.isDraft })
            // Refunded conversions may still hold vault funds. Keep their recovery records beyond the activity limit.
            val recent =
                all
                    .filterNot { it.phase == ReversePhase.REFUNDED }
                    .takeLast(MAX_HISTORY)
                    .map { it.index }
                    .toSet()
            val history = all.filter { it.phase == ReversePhase.REFUNDED || it.index in recent }
            store.set(State(record, history))
        }

    override suspend fun update(record: ReverseSwapRecord) =
        lock.withLock {
            val state = store.get() ?: return@withLock
            val kept = { earlier: ReverseSwapRecord -> if (earlier.index == record.index) record else earlier }
            store.set(State(state.active?.let(kept), state.history.map(kept)))
        }

    override suspend fun clear() = lock.withLock { store.clear() }

    @Serializable
    private data class State(
        val active: ReverseSwapRecord? = null,
        val history: List<ReverseSwapRecord> = emptyList()
    )

    private companion object {
        const val MAX_HISTORY = 100

        val ReverseSwapRecord.isDraft get() = phase == ReversePhase.QUOTED
    }
}
