package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import xyz.justzappit.offramp.p2p.OrderRecipientUpiCache

/**
 * EncryptedSharedPreferences-backed `orderId → recipient VPA` map.
 *
 * Written by the offramp orchestrator the moment the on-chain `OrderPlaced` event is parsed —
 * the user-typed VPA is the only authoritative record of the destination, because the chain's
 * `encUpi` is encrypted to the merchant's pubkey and never re-decryptable by us.
 *
 * Map serialization is intentionally simple — single-row UPDATEs read-modify-write the whole map,
 * which scales fine for the hundreds-of-orders range we expect per user. If the row count ever
 * grows substantially we should switch to per-key entries or a small database.
 *
 * **Concurrency:** `put` is `get → mutate → set`. Two concurrent `put` calls for different orders
 * used to clobber each other (read {a:1}, both compute different mutations, second write wins).
 * A per-instance [Mutex] now serializes the read-modify-write sequence; concurrent calls run
 * back-to-back rather than in parallel. The orchestrator calls `put` at most once per order, so
 * contention is negligible.
 */
class OrderRecipientUpiStorageProvider(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : OrderRecipientUpiCache {
    private val store =
        EncryptedJsonStore(
            encryptedPreferenceProvider = encryptedPreferenceProvider,
            prefKey = PREF_KEY,
            serializer = MapSerializer(String.serializer(), String.serializer()),
        )
    private val writeMutex = Mutex()

    override suspend fun put(orderId: String, recipientUpi: String) {
        writeMutex.withLock {
            val current = store.get() ?: emptyMap()
            if (current[orderId] == recipientUpi) return@withLock
            store.set(current + (orderId to recipientUpi))
        }
    }

    override suspend fun get(orderId: String): String? = store.get()?.get(orderId)

    companion object {
        private const val PREF_KEY = "p2p_order_recipient_upi_v1"
    }
}
