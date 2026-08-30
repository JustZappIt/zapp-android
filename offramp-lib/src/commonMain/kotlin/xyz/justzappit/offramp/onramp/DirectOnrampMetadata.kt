// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.offramp.p2p.CurrencyCode

/** Values sent by p2p.me's official client for each BUY corridor. */
internal data class DirectOnrampMetadata(
    val country: String,
    val paymentMethod: String,
)

internal val CurrencyCode.directOnrampMetadata: DirectOnrampMetadata
    get() =
        when (this) {
            CurrencyCode.Inr -> DirectOnrampMetadata(country = "India", paymentMethod = "UPI")
            CurrencyCode.Brl -> DirectOnrampMetadata(country = "Brazil", paymentMethod = "PIX")
            CurrencyCode.Idr -> DirectOnrampMetadata(country = "Indonesia", paymentMethod = "QRIS")
            CurrencyCode.Ars -> DirectOnrampMetadata(country = "Argentina", paymentMethod = "ALIAS")
            CurrencyCode.Ven -> DirectOnrampMetadata(country = "Venezuela", paymentMethod = "PAGO_MOVIL")
            CurrencyCode.Ngn -> DirectOnrampMetadata(country = "Nigeria", paymentMethod = "NIP")
            CurrencyCode.Cop -> DirectOnrampMetadata(country = "Colombia", paymentMethod = "TRANSFERENCIA")
            CurrencyCode.Bob -> DirectOnrampMetadata(country = "Bolivia", paymentMethod = "QR_SIMPLE")
            CurrencyCode.Cup -> DirectOnrampMetadata(country = "Cuba", paymentMethod = "TRANSFERMOVIL")
            CurrencyCode.Ecu -> DirectOnrampMetadata(country = "Ecuador", paymentMethod = "TRANSFERENCIA")
            CurrencyCode.Pen -> DirectOnrampMetadata(country = "Peru", paymentMethod = "YAPE_PLIN_CCI")
            CurrencyCode.Php -> DirectOnrampMetadata(country = "Philippines", paymentMethod = "INSTAPAY")
        }

/** Matches the frontend's two-decimal truncation after converting seconds to minutes. */
internal fun Long.toMinutes(): String {
    val hundredths = this * HUNDRED / SECONDS_PER_MINUTE
    val whole = hundredths / HUNDRED
    val fraction = hundredths % HUNDRED
    return when {
        fraction == 0L -> whole.toString()
        fraction % TEN == 0L -> "$whole.${fraction / TEN}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val HUNDRED = 100L
private const val TEN = 10L
