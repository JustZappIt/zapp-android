package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable

@Serializable
data class InvestSettings(
    /** ISO 3166-1 alpha-2; null until confirmed on the gate. */
    val countryCode: String? = null,
    val qualifiedInvestor: Boolean = false,
    val setupComplete: Boolean = false,
) {
    val eligibility: InvestEligibility? get() = countryCode?.let(InvestEligibility::of)

    /** Whether buying may be offered. */
    val isAvailable: Boolean
        get() =
            when (eligibility) {
                null, InvestEligibility.PROHIBITED -> false
                InvestEligibility.RESTRICTED -> qualifiedInvestor
                InvestEligibility.ELIGIBLE -> true
            }
}

interface InvestSettingsRepository {
    val settings: Flow<InvestSettings>

    suspend fun get(): InvestSettings

    suspend fun setResidence(
        countryCode: String,
        qualifiedInvestor: Boolean,
    )

    suspend fun completeSetup()
}

/**
 * Kept in encrypted preferences, which a wallet reset clears. Settings that can't be read are cleared once and
 * asked for again, rather than crashing the PAY tab that reads them.
 */
internal class InvestSettingsRepositoryImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : InvestSettingsRepository {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, InvestSettings.serializer())

    override val settings: Flow<InvestSettings> =
        store
            .observe()
            .map { it ?: InvestSettings() }
            .retryWhen { e, attempt ->
                val isRepairable = e is StoreCorruptedException && attempt == 0L
                if (isRepairable) forgetUnreadable(e)
                isRepairable
            }

    override suspend fun get(): InvestSettings =
        try {
            store.get() ?: InvestSettings()
        } catch (e: StoreCorruptedException) {
            forgetUnreadable(e)
            InvestSettings()
        }

    private suspend fun forgetUnreadable(e: Throwable) {
        Twig.warn(e) { "Invest settings can't be read; clearing them, so the country is asked again" }
        store.clear()
    }

    override suspend fun setResidence(
        countryCode: String,
        qualifiedInvestor: Boolean,
    ) {
        require(countryCode.length == 2 && countryCode.all { it in 'A'..'Z' }) { "countryCode must be ISO alpha-2" }
        store.set(get().copy(countryCode = countryCode, qualifiedInvestor = qualifiedInvestor))
    }

    override suspend fun completeSetup() {
        store.set(get().copy(setupComplete = true))
    }

    private companion object {
        const val PREF_KEY = "invest_settings_v1"
    }
}
