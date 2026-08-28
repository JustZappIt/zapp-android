// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.serialization.Serializable

/**
 * The p2p.me corridors Zapp can Scan & Pay on: the SDK's `COUNTRY_OPTIONS` entries that are
 * neither `disabled` nor carry `"PAY"` in `disabledPaymentTypes`. Zapp only ever places PAY
 * orders, so a market the SDK lists for BUY/SELL alone stays out of this enum — MEX is exactly
 * that, and the Revolut USD/EUR rails are disabled outright.
 */
@Serializable
enum class CurrencyCode(
    val code: String,
    val precision: Int,
    val symbol: String,
) {
    Inr("INR", 2, "₹"),
    Brl("BRL", 2, "R$"),
    Idr("IDR", 0, "Rp"),
    Ars("ARS", 2, "$"),
    Ven("VEN", 2, "Bs"),
    Ngn("NGN", 2, "₦"),
    Cop("COP", 2, "$"),
    Bob("BOB", 2, "Bs."),
    Cup("CUP", 2, "$"),

    /** USD-denominated; the SDK's `internationalFormat` for it is literally "USD". */
    Ecu("ECU", 2, "$"),
    Pen("PEN", 2, "S/"),
    Php("PHP", 2, "₱"),
    ;

    /**
     * True when the payment address is a blob rather than a readable handle, so the UI ellipsizes
     * it: VEN is the whole base64 Pago Móvil envelope, PEN/PHP/BOB the entire EMVCo payload, ECU an
     * opaque DeUna hash. A VPA, CUP's `phone|card` and the merchant names pass through in full.
     */
    val paymentAddressIsOpaque: Boolean
        get() = this == Ven || this == Pen || this == Php || this == Bob || this == Ecu

    override fun toString(): String = code

    companion object {
        fun fromCode(code: String): CurrencyCode =
            fromCodeOrNull(code) ?: throw IllegalArgumentException("Unknown currency code: '$code'")

        fun fromCodeOrNull(code: String): CurrencyCode? {
            val normalised = code.uppercase()
            return entries.firstOrNull { it.code == normalised }
        }
    }
}
