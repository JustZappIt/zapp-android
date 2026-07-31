package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import xyz.justzappit.offramp.p2p.CurrencyCode

interface PreferredP2pPaymentMethodProvider : StorageProvider<CurrencyCode>

class PreferredP2pPaymentMethodProviderImpl(
    override val preferenceHolder: StandardPreferenceProvider,
) : BaseStorageProvider<CurrencyCode>(),
    PreferredP2pPaymentMethodProvider {
    override val default: PreferenceDefault<CurrencyCode> =
        PreferredP2pPaymentMethodPreferenceDefault(PreferenceKey("preferred_p2p_payment_method_currency"))
}

private class PreferredP2pPaymentMethodPreferenceDefault(
    override val key: PreferenceKey
) : PreferenceDefault<CurrencyCode> {
    override suspend fun getValue(preferenceProvider: PreferenceProvider): CurrencyCode =
        preferenceProvider
            .getString(key)
            ?.let { CurrencyCode.fromCodeOrNull(it) }
            ?: CurrencyCode.Inr

    override suspend fun putValue(
        preferenceProvider: PreferenceProvider,
        newValue: CurrencyCode
    ) {
        preferenceProvider.putString(key, newValue.code)
    }
}
