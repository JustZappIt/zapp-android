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

@Serializable
data class PrivateUsdSendRecord(
    val txHash: String,
    val withdraw: Boolean,
    val token: String,
    /** Token base units taken from the private balance. */
    val amount: String,
    val to: String,
    /** Unix seconds. */
    val sentAt: Long,
)

/** The sends and withdrawals made from this device, oldest first. */
class PrivateUsdSendLog(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, Log.serializer())
    private val lock = Mutex()

    val observe: Flow<List<PrivateUsdSendRecord>> =
        store
            .observe()
            .map { it?.sends.orEmpty() }
            .catch { e ->
                Twig.warn(e) { "Private USD: the send log is unreadable" }
                emit(emptyList())
            }

    suspend fun add(record: PrivateUsdSendRecord) =
        lock.withLock {
            val sends = store.get()?.sends.orEmpty()
            store.set(Log((sends + record).takeLast(MAX_SENDS)))
        }

    @Serializable
    private data class Log(
        val sends: List<PrivateUsdSendRecord> = emptyList()
    )

    private companion object {
        const val PREF_KEY = "private_usd_sends_v1"
        const val MAX_SENDS = 100
    }
}
