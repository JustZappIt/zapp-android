// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.decimalDivide
import xyz.justzappit.evm.math.decimalMovePointRight
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalToLong
import xyz.justzappit.evm.math.decimalToPlainString

/**
 * Builds the `upi://pay?…` URI the merchant decrypts and runs through `parseQR` to settle an
 * accepted PAY order. Sending a bare VPA + `updatedAmount=0` to setSellOrderUpi makes the Diamond
 * auto-cancel the order in the same tx; the merchant pool also runs the SDK's `parseUPI` on the
 * decrypted payload, so the input must parse with at least `pa` and `am` (§6 of the findings).
 *
 * Shape mirrors `@p2pdotme/sdk` v1.1.7 / `qr-parsers/parsers/inr.ts`:
 *   upi://pay?pa=<vpa>&pn=<payee-name>&am=<inr-amount>&cu=INR
 */
object UpiPayUri {
    /**
     * Decimals retained in the URI's `am=` field. Diamond reads `am=` from the merchant-decrypted
     * URI and re-derives USDC at setSellOrderUpi; if the caller's `updatedAmount` doesn't equal
     * `floor(am × 1e6 / sellPrice)` the order is atomically cancelled. Surfaced so callers can snap
     * their INR amount to the same precision before computing `updatedAmount`.
     */
    const val INR_DECIMAL_PLACES = 2

    fun build(vpa: String, payeeName: String? = null, inrAmount: BigDecimal, currencyCode: String = "INR"): String {
        require(vpa.isNotBlank()) { "vpa must not be blank" }
        require(inrAmount.signum() > 0) { "inrAmount must be positive" }
        val amStr =
            decimalToPlainString(
                decimalSetScale(inrAmount, INR_DECIMAL_PLACES, DecimalRounding.DOWN),
            )
        return buildString {
            append("upi://pay?")
            append("pa=").append(percentEncode(vpa))
            if (!payeeName.isNullOrBlank()) {
                append("&pn=").append(percentEncode(payeeName))
            }
            append("&am=").append(amStr)
            append("&cu=").append(percentEncode(currencyCode))
        }
    }

    /** Mirrors `parseAmount` in the SDK: floor(fiat / sellPrice, 6) — never round up. */
    fun parsedUsdcMicros(inrAmount: BigDecimal, sellPriceInrPerUsdc: BigDecimal): Long {
        require(sellPriceInrPerUsdc.signum() > 0) { "sellPrice must be positive" }
        val usdc = decimalDivide(inrAmount, sellPriceInrPerUsdc, Usdc6.DECIMALS, DecimalRounding.DOWN)
        return decimalToLong(decimalMovePointRight(usdc, Usdc6.DECIMALS))
    }

    private fun percentEncode(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            if (
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~' || c == '@'
            ) {
                out.append(c)
            } else {
                for (b in c.toString().encodeToByteArray()) {
                    out.append('%')
                    out.append(HEX[(b.toInt() shr HEX_NIBBLE) and HEX_MASK])
                    out.append(HEX[b.toInt() and HEX_MASK])
                }
            }
        }
        return out.toString()
    }

    private const val HEX_NIBBLE = 4
    private const val HEX_MASK = 0xF
    private val HEX = "0123456789ABCDEF".toCharArray()
}
