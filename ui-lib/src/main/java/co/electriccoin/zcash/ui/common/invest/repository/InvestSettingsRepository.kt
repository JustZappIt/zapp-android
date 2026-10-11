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

/** The answers Invest keeps on this phone only: where the user lives and whether setup has run. */
@Serializable
data class InvestSettings(
    /** ISO 3166-1 alpha-2, as the user confirmed it on the gate; null until they have. */
    val countryCode: String? = null,
    /** The user attested to being a qualified or professional investor (asked only where required). */
    val qualifiedInvestor: Boolean = false,
    /** Setup (key derived, first sign-in) finished once; the intro isn't shown again. */
    val setupComplete: Boolean = false,
) {
    val eligibility: InvestEligibility? get() = countryCode?.let(InvestEligibility::of)

    /** Whether Invest may be offered: a country is known, it isn't prohibited, and a restricted one is attested. */
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
 * Kept in encrypted preferences, which [co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase]
 * clears with the wallet, so a new wallet on the phone asks again.
 *
 * Settings that can't be read are cleared, and count as never given: Invest asks for the country again, and nothing
 * can be bought meanwhile. Throwing instead would crash the PAY tab, which reads them at every launch. Holdings are
 * untouched: they live in the private account, not here.
 */
internal class InvestSettingsRepositoryImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : InvestSettingsRepository {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, InvestSettings.serializer())

    // Cleared once, then read again; settings that still can't be read after that are a real fault, and surface.
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
