package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.offramp.onramp.OnrampCheckpoint
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpointStore

interface OnrampCheckpointStorageProvider {
    suspend fun get(): OnrampCheckpoint?

    suspend fun store(checkpoint: OnrampCheckpoint)

    suspend fun clear()

    fun observe(): Flow<OnrampCheckpoint?>
}

internal class OnrampCheckpointStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : OnrampCheckpointStorageProvider {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, OnrampCheckpoint.serializer())

    override suspend fun get(): OnrampCheckpoint? = store.get()

    override suspend fun store(checkpoint: OnrampCheckpoint) = store.set(checkpoint)

    override suspend fun clear() = store.clear()

    override fun observe(): Flow<OnrampCheckpoint?> = store.observe()

    private companion object {
        const val PREF_KEY = "p2p_onramp_checkpoint_v1"
    }
}

internal class OnrampZecDeliveryCheckpointStoreImpl(
    private val storage: OnrampCheckpointStorageProvider,
) : OnrampZecDeliveryCheckpointStore {
    private val mutex = Mutex()

    override suspend fun save(orderId: String, checkpoint: OnrampZecDeliveryCheckpoint) {
        mutex.withLock {
            val parent = checkNotNull(storage.get()) { "Onramp checkpoint is missing" }
            require(parent.id == orderId) { "Onramp checkpoint belongs to another order" }
            require(parent.phase == OnrampPhase.COMPLETED) { "P2P settlement is not complete" }
            require(parent.destination == OnrampDestination.ZCASH) { "Onramp destination is not Zcash" }
            storage.store(parent.copy(zecDelivery = checkpoint))
        }
    }
}
