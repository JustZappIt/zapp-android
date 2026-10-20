package co.electriccoin.zcash.ui.common.invest.model

/**
 * Who may be offered Ondo stocks, by ISO 3166-1 alpha-2 country of residence, from
 * docs.ondo.finance/ondo-stocks/eligibility. Ondo's prohibited regions of Ukraine can't be told apart from UA.
 */
enum class InvestEligibility {
    ELIGIBLE,

    /** Only for qualified or professional investors, who attest to it on the gate. */
    RESTRICTED,

    PROHIBITED,
    ;

    companion object {
        // CH is on Ondo's restricted list but closed to individuals, so no attestation opens it.
        private val PROHIBITED_COUNTRIES =
            setOf(
                "AF",
                "BY",
                "CA",
                "CH",
                "CU",
                "IR",
                "KP",
                "LY",
                "MM",
                "RU",
                "SD",
                "SO",
                "SS",
                "SY",
                "US",
            )

        private val EEA =
            setOf(
                "AT",
                "BE",
                "BG",
                "HR",
                "CY",
                "CZ",
                "DK",
                "EE",
                "FI",
                "FR",
                "DE",
                "GR",
                "HU",
                "IE",
                "IT",
                "LV",
                "LT",
                "LU",
                "MT",
                "NL",
                "PL",
                "PT",
                "RO",
                "SK",
                "SI",
                "ES",
                "SE",
                "IS",
                "LI",
                "NO",
            )

        private val RESTRICTED_COUNTRIES = EEA + setOf("BR", "HK", "GB", "SG", "MY")

        fun of(countryCode: String): InvestEligibility =
            when (countryCode.uppercase()) {
                in PROHIBITED_COUNTRIES -> PROHIBITED
                in RESTRICTED_COUNTRIES -> RESTRICTED
                else -> ELIGIBLE
            }
    }
}
