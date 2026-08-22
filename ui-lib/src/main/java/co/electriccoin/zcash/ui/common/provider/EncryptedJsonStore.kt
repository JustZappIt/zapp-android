package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Shared encrypted-prefs JSON store. Returns `null` for an absent key, throws
 * [StoreCorruptedException] for a present-but-undecodable blob: silently treating corrupt as
 * absent would let `getOrCreate` overwrite still-encrypted data (e.g. the relay key sealing past
 * orders' merchant UPI). Schema migration: version the key, don't loosen decode.
 *
 * [strict] chooses what a field this build does not recognise means. The default tolerates one,
 * which is right for a cache or a checkpoint: a record written by a newer build still reads here,
 * and the unknown field is dropped on the next write. For a store that is the only copy of
 * something — key material with no other recovery path — that silent drop is data loss, so those
 * pass true and take a [StoreCorruptedException] instead. Refusing to read is recoverable, because
 * a mutation that cannot read does not write; dropping a field is not.
 */
internal class EncryptedJsonStore<T>(
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    prefKey: String,
    private val serializer: KSerializer<T>,
    strict: Boolean = false,
) {
    private val key = PreferenceKey(prefKey)
    private val json =
        Json {
            ignoreUnknownKeys = !strict
            explicitNulls = false
        }

    /** Returns null when the key is absent. Throws [StoreCorruptedException] when present-but-undecodable. */
    suspend fun get(): T? = encryptedPreferenceProvider().getString(key)?.let(::decode)

    suspend fun set(value: T) {
        encryptedPreferenceProvider().putString(key, json.encodeToString(serializer, value))
    }

    suspend fun clear() {
        encryptedPreferenceProvider().putString(key, null)
    }

    /** Same absent/corrupt contract as [get]: [StoreCorruptedException] is thrown inside the flow. */
    fun observe(): Flow<T?> =
        flow {
            emitAll(encryptedPreferenceProvider().observe(key).map { raw -> raw?.let(::decode) })
        }

    // IllegalArgumentException, not SerializationException: kotlinx derives the latter from the
    // former, and a model whose `init` rejects the decoded values (a version it doesn't know, a
    // phase missing its recovery handles) throws a plain IAE. Both mean present-but-undecodable;
    // letting the IAE escape raw would crash whichever screen reads the key, on every launch.
    private fun decode(raw: String): T =
        try {
            json.decodeFromString(serializer, raw)
        } catch (e: IllegalArgumentException) {
            // Don't interpolate e.message or chain the cause: kotlinx embeds a "JSON input:"
            // snippet of the raw input — here the DECRYPTED blob (relay private key, recipient
            // UPI data) — and callers legitimately Twig.warn this exception.
            throw StoreCorruptedException(
                "Encrypted-prefs blob for key ${key.key} failed to decode (${e::class.simpleName})"
            )
        }
}

/** Present-but-undecodable blob from [EncryptedJsonStore]. Distinct from absence (`null`). */
class StoreCorruptedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
