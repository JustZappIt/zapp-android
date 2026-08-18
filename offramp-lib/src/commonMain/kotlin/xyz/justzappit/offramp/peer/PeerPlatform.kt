// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.util.toHex

/**
 * The ungated rails Zapp offers as a maker. Everything protocol-side is identical across them, so a
 * `when` on a platform anywhere outside this file is a design smell: the differences are the handle
 * format and the currency set, and both live here.
 *
 * Wise, Venmo, Cash App and PayPal are deeper corridors but sit behind a maker identity attestation
 * or the atomic access policy, neither of which this flow can satisfy.
 */
@Serializable
enum class PeerPlatform(
    val wireName: String,
    val currencies: Set<PeerCurrency>,
    val defaultCurrencies: Set<PeerCurrency>,
    val validatesHandleLive: Boolean,
) {
    REVOLUT(
        wireName = "revolut",
        currencies = PeerCurrency.entries.toSet(),
        defaultCurrencies = setOf(PeerCurrency.EUR, PeerCurrency.GBP, PeerCurrency.USD),
        validatesHandleLive = true,
    ),
    ZELLE(
        wireName = "zelle",
        currencies = setOf(PeerCurrency.USD),
        defaultCurrencies = setOf(PeerCurrency.USD),
        validatesHandleLive = false,
    ),
    CHIME(
        wireName = "chime",
        currencies = setOf(PeerCurrency.USD),
        defaultCurrencies = setOf(PeerCurrency.USD),
        validatesHandleLive = false,
    ),
    MONZO(
        wireName = "monzo",
        currencies = setOf(PeerCurrency.GBP),
        defaultCurrencies = setOf(PeerCurrency.GBP),
        validatesHandleLive = true,
    ),
    ;

    val paymentMethodHash: PaymentMethodHash =
        PaymentMethodHash.parse(keccak256(wireName.encodeToByteArray()).toHex())

    /** Whether the user picks currencies at all, or the rail's single currency is simply shown. */
    val offersCurrencyChoice: Boolean get() = currencies.size > 1

    /**
     * The only place a raw handle becomes a [PayeeHandle]. Chime is the one that matters: the
     * curator rejects a ChimeSign without its leading `$`, so an unprefixed handle is repaired
     * rather than sent to fail.
     *
     * On three of the four rails the curator hashes this string verbatim into `payeeDetailsHash`,
     * so a capital registers a hash no proof can reproduce and funds a deposit nobody can claim.
     * Every live payee sampled on those three is lowercase, and the curator refuses an uppercase
     * one outright.
     */
    fun normalizeHandle(raw: String): PayeeHandle {
        val trimmed = raw.trim()
        val normalized =
            when (this) {
                REVOLUT -> trimmed.trimStart(REVTAG_AT_SIGN).lowercase()

                ZELLE -> trimmed.lowercase()

                CHIME -> CHIME_SIGN_PREFIX + trimmed.trimStart(CHIME_SIGN_CHAR).lowercase()

                // The exception, and lowercasing it to match the others would be a rule with a false
                // reason behind it. Monzo's hash is taken over the Monzo user id the curator resolves
                // from the monzo.me page rather than over this string, and that page resolves the
                // username case-insensitively, so the case typed here never reaches the hash.
                MONZO -> trimmed
            }
        return PayeeHandle.ofNormalized(normalized)
    }

    /**
     * Cheap pre-flight so an obviously malformed handle produces an inline hint instead of a
     * network round trip. The curator stays authoritative: this only ever rules a handle out.
     */
    fun hasPlausibleFormat(handle: PayeeHandle): Boolean =
        when (this) {
            REVOLUT -> REVTAG.matches(handle.value)

            // Email only: the curator rejects every US phone spelling, so offering one sends the
            // user down a path that can only end in "we could not confirm that handle".
            ZELLE -> EMAIL.matches(handle.value)

            CHIME -> CHIME_SIGN.matches(handle.value)

            MONZO -> MONZO_USERNAME.matches(handle.value)
        }

    companion object {
        const val CHIME_SIGN_PREFIX = "$"
        private const val CHIME_SIGN_CHAR = '$'

        fun fromWireNameOrNull(wireName: String): PeerPlatform? =
            entries.firstOrNull { it.wireName == wireName.lowercase() }

        fun fromPaymentMethodHashOrNull(hash: String): PeerPlatform? {
            val normalised = PaymentMethodHash.parse(hash)
            return entries.firstOrNull { it.paymentMethodHash == normalised }
        }

        private const val REVTAG_AT_SIGN = '@'

        // Underscores are real: a live revtag carrying one has fulfilled intents, though Revolut's
        // own help centre claims alphanumerics only.
        private val REVTAG = Regex("^[a-z0-9_]{3,32}$")
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")

        // Probed against the curator: one leading `$`, at least one character, no uppercase. It
        // accepts single characters, 80-char signs and spaces, so a tighter rule rejects valid ones.
        private val CHIME_SIGN = Regex("^\\$[^A-Z]+$")
        private val MONZO_USERNAME = Regex("^[A-Za-z0-9._-]{2,32}$")
    }
}
