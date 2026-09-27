// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapStore

/**
 * [AtomicSwapStore] in the encrypted preferences. Strict: the index counter must never go back, so an
 * unreadable blob fails loudly instead of being replaced.
 */
class AtomicSwapStoreImpl(
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : AtomicSwapStore {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, State.serializer(), strict = true)
    private val lock = Mutex()

    val observeActive: Flow<AtomicSwapRecord?> = store.observe().map { it?.active }

    /** Every swap accepted here, oldest first, the active one included. */
    val observeHistory: Flow<List<AtomicSwapRecord>> =
        store.observe().map { state ->
            state?.history.orEmpty().filterNot { it.index == state?.active?.index } + listOfNotNull(state?.active)
        }

    override suspend fun takeIndex(): Int =
        lock.withLock {
            val state = state()
            store.set(state.copy(nextIndex = state.nextIndex + 1))
            state.nextIndex
        }

    override suspend fun active(): AtomicSwapRecord? = store.get()?.active

    override suspend fun save(record: AtomicSwapRecord) =
        lock.withLock {
            val state = state()
            val history = (state.history.filterNot { it.index == record.index } + record).takeLast(MAX_HISTORY)
            store.set(state.copy(active = record, history = history))
        }

    private suspend fun state(): State = store.get() ?: State(nextIndex = legacyNextIndex())

    // The first store's records are dropped, but its counter carries over: an index is never reused.
    private suspend fun legacyNextIndex(): Int =
        encryptedPreferenceProvider()
            .getString(LEGACY_KEY)
            ?.let { legacyJson.decodeFromString(Legacy.serializer(), it).nextIndex }
            ?: 0

    @Serializable
    private data class State(
        val nextIndex: Int = 0,
        val active: AtomicSwapRecord? = null,
        val history: List<AtomicSwapRecord> = emptyList(),
    )

    @Serializable
    private data class Legacy(
        val nextIndex: Int = 0
    )

    private companion object {
        const val PREF_KEY = "atomicswap_state_v2"
        const val MAX_HISTORY = 100
        val LEGACY_KEY = PreferenceKey("atomicswap_state_v1")
        val legacyJson = Json { ignoreUnknownKeys = true }
    }
}
