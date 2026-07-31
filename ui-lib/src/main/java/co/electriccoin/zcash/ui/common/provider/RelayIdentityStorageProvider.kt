package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import xyz.justzappit.offramp.p2p.RelayIdentity
import xyz.justzappit.offramp.p2p.RelayIdentityStore

/** EncryptedSharedPreferences-backed [RelayIdentityStore]. See its kdoc for why this must persist. */
class RelayIdentityStorageProvider(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : RelayIdentityStore {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, RelayIdentity.serializer())

    override suspend fun get(): RelayIdentity? = store.get()

    override suspend fun set(identity: RelayIdentity) = store.set(identity)

    companion object {
        private const val PREF_KEY = "p2p_offramp_relay_identity_v1"
    }
}
