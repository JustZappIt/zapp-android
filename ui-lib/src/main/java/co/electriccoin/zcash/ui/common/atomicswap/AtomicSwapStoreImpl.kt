// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapStore

/**
 * [AtomicSwapStore] in the encrypted preferences. Strict: the index counter must never go back, so an
 * unreadable blob fails loudly instead of being replaced.
 */
class AtomicSwapStoreImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : AtomicSwapStore {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, State.serializer(), strict = true)
    private val lock = Mutex()

    override suspend fun takeIndex(): Int =
        lock.withLock {
            val state = store.get() ?: State()
            store.set(state.copy(nextIndex = state.nextIndex + 1))
            state.nextIndex
        }

    override suspend fun active(): AtomicSwapRecord? = store.get()?.active

    override suspend fun save(record: AtomicSwapRecord) =
        lock.withLock {
            store.set((store.get() ?: State()).copy(active = record))
        }

    @Serializable
    private data class State(
        val nextIndex: Int = 0,
        val active: AtomicSwapRecord? = null,
    )

    private companion object {
        const val PREF_KEY = "atomicswap_state_v1"
    }
}
