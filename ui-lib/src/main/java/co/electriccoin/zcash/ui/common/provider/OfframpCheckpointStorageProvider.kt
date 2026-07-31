package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import kotlinx.coroutines.flow.Flow
import xyz.justzappit.offramp.orchestrator.OfframpCheckpoint

/**
 * Persists the single in-flight UPI offramp checkpoint so a process death or config change between
 * on-chain broadcasts doesn't orphan the user's USDC. v1 holds one slot; the API shape supports
 * lifting that limit later without changing the wire format.
 */
interface OfframpCheckpointStorageProvider {
    suspend fun get(): OfframpCheckpoint?

    suspend fun store(checkpoint: OfframpCheckpoint)

    suspend fun clear()

    fun observe(): Flow<OfframpCheckpoint?>
}

internal class OfframpCheckpointStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : OfframpCheckpointStorageProvider {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, OfframpCheckpoint.serializer())

    override suspend fun get(): OfframpCheckpoint? = store.get()

    override suspend fun store(checkpoint: OfframpCheckpoint) = store.set(checkpoint)

    override suspend fun clear() = store.clear()

    override fun observe(): Flow<OfframpCheckpoint?> = store.observe()

    companion object {
        private const val PREF_KEY = "upi_offramp_checkpoint_v1"
    }
}
