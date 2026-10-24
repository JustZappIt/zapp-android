// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositScan
import xyz.justzappit.offramp.deposits.DepositStore

/**
 * The wallet's deposits and the last scan of its deposit addresses, kept before any request leaves the wallet: each
 * holds a signed authorization and a note nobody can build again. Strict, so a field this build doesn't know fails
 * loudly instead of being dropped, and one that can't be read is never written over.
 */
class PrivateUsdDepositStore(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : DepositStore {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, Book.serializer(), strict = true)
    private val lock = Mutex()

    /** Every deposit kept, newest first, for Activity: one that can't be read shows none there, and stays as it is. */
    val observe: Flow<List<DepositRecord>> =
        store
            .observe()
            .map { it?.records.orEmpty() }
            .catch { e ->
                Twig.warn(e) { "Private USD: the deposits are unreadable" }
                emit(emptyList())
            }

    override suspend fun records(): List<DepositRecord> = lock.withLock { read().records }

    override suspend fun keep(records: List<DepositRecord>) =
        lock.withLock {
            val book = read()
            val kept = book.records.toMutableList()
            records.forEach { record ->
                val at = kept.indexOfFirst { it.id == record.id }
                if (at >= 0) kept[at] = record else kept.add(0, record)
            }
            store.set(book.copy(records = retained(kept)))
        }

    override suspend fun scan(): DepositScan? = lock.withLock { read().scan }

    override suspend fun keepScan(scan: DepositScan) = lock.withLock { store.set(read().copy(scan = scan)) }

    // Never a reset: what a read can't decode throws, and nothing is written over it.
    private suspend fun read(): Book = store.get() ?: Book()

    // Every deposit under way, and the newest of the rest.
    private fun retained(records: List<DepositRecord>): List<DepositRecord> {
        val finished =
            records
                .filter { it.state.isFinished }
                .take(MAX_FINISHED)
                .map { it.id }
                .toSet()
        return records.filter { !it.state.isFinished || it.id in finished }
    }

    @Serializable
    private data class Book(
        /** Newest first. */
        val records: List<DepositRecord> = emptyList(),
        val scan: DepositScan? = null,
    )

    private companion object {
        const val PREF_KEY = "private_usd_deposits_v1"
        const val MAX_FINISHED = 100
    }
}
