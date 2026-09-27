package co.electriccoin.zcash.ui.common.invest.model

/**
 * Who may be offered Ondo stocks, by country of residence (ISO 3166-1 alpha-2), from Ondo's eligibility
 * page (docs.ondo.finance/ondo-stocks/eligibility, read 2026-09-25). Needs legal review before release: the
 * source can change, and this list is what the demo build gates on, not legal advice.
 */
enum class InvestEligibility {
    ELIGIBLE,

    /** Only for qualified or professional investors under local rules; the gate asks for that attestation. */
    RESTRICTED,

    /** Never offered. */
    PROHIBITED,
    ;

    companion object {
        private val PROHIBITED_COUNTRIES = setOf("US", "CA")

        // EEA = the 27 EU member states plus Iceland, Liechtenstein and Norway.
        private val EEA =
            setOf(
                "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV",
                "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO",
            )

        private val RESTRICTED_COUNTRIES = EEA + setOf("BR", "HK", "GB", "SG", "MY", "CH")

        fun of(countryCode: String): InvestEligibility =
            when (countryCode.uppercase()) {
                in PROHIBITED_COUNTRIES -> PROHIBITED
                in RESTRICTED_COUNTRIES -> RESTRICTED
                else -> ELIGIBLE
            }
    }
}
