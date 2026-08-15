package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId

/**
 * The unfinished Peer cash-outs, keyed by attempt. Several can be in flight at once, and each is
 * cleared as soon as its deposit is indexed, after which that order is recoverable from the chain
 * alone.
 *
 * Keyed rather than a single blob because the amount, the rail and the transaction hashes of one
 * attempt must never be readable as another's: resolving order B's amount against order A's
 * `createDeposit` hash resolves the wrong deposit.
 */
interface PeerCashOutCheckpointStorageProvider {
    suspend fun get(id: PeerCashOutId): PeerCashOutCheckpoint?

    suspend fun all(): List<PeerCashOutCheckpoint>

    suspend fun store(checkpoint: PeerCashOutCheckpoint)

    suspend fun clear(id: PeerCashOutId)

    fun observe(): Flow<List<PeerCashOutCheckpoint>>
}

internal class PeerCashOutCheckpointStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : PeerCashOutCheckpointStorageProvider {
    private val store =
        EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, PeerCashOutCheckpointBook.serializer())

    // Concurrent attempts each read-modify-write this key, and a lost write is a lost recovery
    // record for money already broadcast.
    private val mutex = Mutex()

    override suspend fun get(id: PeerCashOutId): PeerCashOutCheckpoint? =
        all().firstOrNull { it.id == id }

    override suspend fun all(): List<PeerCashOutCheckpoint> = store.get()?.entries.orEmpty()

    override suspend fun store(checkpoint: PeerCashOutCheckpoint) =
        mutex.withLock {
            val others = all().filterNot { it.id == checkpoint.id }
            store.set(PeerCashOutCheckpointBook(entries = others + checkpoint))
        }

    override suspend fun clear(id: PeerCashOutId) =
        mutex.withLock {
            val remaining = all().filterNot { it.id == id }
            store.set(PeerCashOutCheckpointBook(entries = remaining))
        }

    override fun observe(): Flow<List<PeerCashOutCheckpoint>> = store.observe().map { it?.entries.orEmpty() }

    companion object {
        // v1 held one anonymous checkpoint. It cannot be proven to belong to any particular attempt,
        // and guessing is the failure this key bump exists to fix, so it is dropped rather than
        // migrated. The order itself stays reachable through the indexer list.
        private const val PREF_KEY = "peer_cash_out_checkpoint_v2"
    }
}

@Serializable
internal data class PeerCashOutCheckpointBook(
    val entries: List<PeerCashOutCheckpoint> = emptyList(),
)
