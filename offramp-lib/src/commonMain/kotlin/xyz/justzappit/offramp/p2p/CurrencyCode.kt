// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.serialization.Serializable

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
    ;

    val paymentAddressIsOpaque: Boolean get() = this == Ven

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
