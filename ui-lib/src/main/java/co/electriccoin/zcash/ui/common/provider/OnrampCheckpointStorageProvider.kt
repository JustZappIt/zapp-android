package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import kotlinx.coroutines.flow.Flow
import xyz.justzappit.offramp.onramp.OnrampCheckpoint

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
