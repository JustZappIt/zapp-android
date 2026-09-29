// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapStore

class ReverseSwapStoreImpl(
    preferences: EncryptedPreferenceProvider
) : ReverseSwapStore {
    private val store = EncryptedJsonStore(preferences, "reverse_swap_v1", State.serializer(), strict = true)
    private val lock = Mutex()
    val observe = store.observe().map { it?.active }
    val history = store.observe().map { it?.history.orEmpty() }

    override suspend fun active(): ReverseSwapRecord? = store.get()?.active

    override suspend fun save(record: ReverseSwapRecord) =
        lock.withLock {
            val state = store.get() ?: State()
            store.set(State(record, state.history.filterNot { it.index == record.index } + record))
        }

    @Serializable
    private data class State(
        val active: ReverseSwapRecord? = null,
        val history: List<ReverseSwapRecord> = emptyList()
    )
}
