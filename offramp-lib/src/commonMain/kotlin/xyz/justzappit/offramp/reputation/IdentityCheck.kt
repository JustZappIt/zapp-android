// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import xyz.justzappit.offramp.p2p.CurrencyCode

/**
 * The two hosted checks p2p.me offers beside the social logins. Each runs in its own widget,
 * signs its own attestation and is submitted to the ReputationManager from the user's smart
 * account, which awards reputation the same way a social verification does.
 */
enum class IdentityCheck(
    val submitSignature: String,
    val verifiedSignature: String,
    val rpGetterSignature: String,
) {
    /** A face-only liveness challenge. No document, so no country. */
    Liveness(
        submitSignature = "submitLivenessAttestation(bytes32,uint256,uint256,bytes)",
        verifiedSignature = "livenessVerified(address)",
        rpGetterSignature = "livenessRp()",
    ),

    /** Passport scan, then liveness, then a face match against the document. */
    Passport(
        submitSignature = "submitKycAttestation(bytes32,uint256,uint256,bytes)",
        verifiedSignature = "kycVerified(address)",
        rpGetterSignature = "kycRp()",
    ),
    ;

    /** p2p.me's own client offers liveness everywhere but India, and passport where it has a country. */
    fun isOfferedIn(currency: CurrencyCode): Boolean =
        when (this) {
            Liveness -> currency != CurrencyCode.Inr
            Passport -> currency.passportCountry != null
        }
}

/** ISO country the passport widget is opened for, from p2p.me's `KYC_COUNTRY_BY_CURRENCY`. */
val CurrencyCode.passportCountry: String?
    get() =
        when (this) {
            CurrencyCode.Inr -> "IN"
            CurrencyCode.Brl -> "BR"
            CurrencyCode.Idr -> "ID"
            CurrencyCode.Ars -> "AR"
            CurrencyCode.Ven -> "VE"
            CurrencyCode.Ngn -> "NG"
            CurrencyCode.Cop -> "CO"
            CurrencyCode.Bob -> "BO"
            CurrencyCode.Cup -> "CU"
            CurrencyCode.Ecu -> "EC"
            CurrencyCode.Pen -> "PE"
            CurrencyCode.Php -> "PH"
        }
