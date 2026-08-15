package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.model.P2pRail

interface PreferredP2pPaymentMethodProvider : StorageProvider<P2pRail>

class PreferredP2pPaymentMethodProviderImpl(
    override val preferenceHolder: StandardPreferenceProvider,
) : BaseStorageProvider<P2pRail>(),
    PreferredP2pPaymentMethodProvider {
    override val default: PreferenceDefault<P2pRail> =
        PreferredP2pPaymentMethodPreferenceDefault(PreferenceKey("preferred_p2p_payment_method_currency"))
}

// The key still says "currency" because it holds values written before Peer existed. Renaming it
// would strand every user's existing selection; P2pRail.fromIdOrNull reads both forms instead.
private class PreferredP2pPaymentMethodPreferenceDefault(
    override val key: PreferenceKey
) : PreferenceDefault<P2pRail> {
    override suspend fun getValue(preferenceProvider: PreferenceProvider): P2pRail =
        preferenceProvider
            .getString(key)
            ?.let { P2pRail.fromIdOrNull(it) }
            ?: P2pRail.DEFAULT

    override suspend fun putValue(
        preferenceProvider: PreferenceProvider,
        newValue: P2pRail
    ) {
        preferenceProvider.putString(key, newValue.id)
    }
}
