package co.electriccoin.zcash.ui.screen.invest.gate

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

enum class ResidenceHintSource { SIM, LOCALE }

/** A guess at the user's country for the gate to suggest; the user always confirms or changes it. */
data class ResidenceHint(
    val countryCode: String,
    val source: ResidenceHintSource,
)

fun interface ResidenceHintProvider {
    fun hint(): ResidenceHint?
}

/**
 * The SIM's country first (it says where the phone's line is, which a travelling user can't change by accident),
 * then the phone's region setting. Reads nothing that needs a permission.
 */
class AndroidResidenceHintProvider(
    private val context: Context,
) : ResidenceHintProvider {
    override fun hint(): ResidenceHint? {
        val sim =
            runCatching { context.getSystemService(TelephonyManager::class.java)?.simCountryIso }
                .getOrNull()
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it in isoCountries }
        if (sim != null) return ResidenceHint(sim, ResidenceHintSource.SIM)
        val region =
            Locale
                .getDefault()
                .country
                .uppercase(Locale.ROOT)
                .takeIf { it in isoCountries }
        return region?.let { ResidenceHint(it, ResidenceHintSource.LOCALE) }
    }

    private companion object {
        val isoCountries: Set<String> = Locale.getISOCountries().toSet()
    }
}
