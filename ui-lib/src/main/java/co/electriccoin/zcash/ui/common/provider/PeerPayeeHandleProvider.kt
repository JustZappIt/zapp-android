package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.peer.PayeeHandle
import xyz.justzappit.offramp.peer.PayeeHash
import xyz.justzappit.offramp.peer.PeerPlatform

/**
 * The user's payee handle per platform, so a revtag and a Zelle address can be held at once and
 * registration is not repeated on every order.
 *
 * A revtag is PII. It is encrypted at rest, never logged, and never enters a checkpoint — only the
 * curator hash goes anywhere near the chain.
 */
interface PeerPayeeHandleProvider {
    suspend fun get(platform: PeerPlatform): PeerPayeeRecord?

    suspend fun store(platform: PeerPlatform, handle: PayeeHandle, hash: PayeeHash?)

    suspend fun clear(platform: PeerPlatform)

    fun observe(platform: PeerPlatform): Flow<PeerPayeeRecord?>
}

/** [hash] is null until the curator has registered this handle, which happens once per handle. */
data class PeerPayeeRecord(
    val handle: PayeeHandle,
    val hash: PayeeHash?,
)

internal class PeerPayeeHandleProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : PeerPayeeHandleProvider {
    private val store =
        EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, PeerPayeeHandleMap.serializer())

    // Every write is a read-modify-write of one map holding every platform, so two rails registering
    // at once would each write back a copy taken before the other's entry existed.
    private val mutex = Mutex()

    override suspend fun get(platform: PeerPlatform): PeerPayeeRecord? =
        store
            .get()
            ?.entries
            ?.get(platform.wireName)
            ?.toRecord(platform)

    override suspend fun store(platform: PeerPlatform, handle: PayeeHandle, hash: PayeeHash?) =
        mutex.withLock {
            val current = store.get()?.entries.orEmpty()
            store.set(
                PeerPayeeHandleMap(
                    entries =
                        current +
                            (platform.wireName to PeerPayeeEntry(handle = handle.value, hashHex = hash?.hex)),
                ),
            )
        }

    override suspend fun clear(platform: PeerPlatform) =
        mutex.withLock {
            val current = store.get()?.entries.orEmpty()
            store.set(PeerPayeeHandleMap(entries = current - platform.wireName))
        }

    override fun observe(platform: PeerPlatform): Flow<PeerPayeeRecord?> =
        store.observe().map { it?.entries?.get(platform.wireName)?.toRecord(platform) }

    private fun PeerPayeeEntry.toRecord(platform: PeerPlatform): PeerPayeeRecord? {
        val normalized = runCatching { platform.normalizeHandle(handle) }.getOrNull() ?: return null
        return PeerPayeeRecord(handle = normalized, hash = hashHex?.let(PayeeHash::parseOrNull))
    }

    companion object {
        private const val PREF_KEY = "peer_payee_handles_v1"
    }
}

@Serializable
internal data class PeerPayeeHandleMap(
    val entries: Map<String, PeerPayeeEntry> = emptyMap(),
)

@Serializable
internal data class PeerPayeeEntry(
    val handle: String,
    val hashHex: String? = null,
)
