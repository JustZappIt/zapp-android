// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.decimalDivide
import xyz.justzappit.evm.math.decimalMovePointRight
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalToLong
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.evm.math.plus

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

    /**
     * The buy-side intent: the user pays a merchant, so the merchant's handle is the payee and the
     * on-chain order id rides along in `tr` — that reference is what lets them reconcile the
     * payment against the order, and omitting it strands the money against an order nobody can
     * match.
     *
     * ☠ `am=` takes at most **two** decimals. Fiat is 6dp internally, and passing it raw yields
     * `am=539.25888`, which PhonePe rejects as "declined for security reasons" — a malformed
     * amount that reads to the user exactly like a payment failure, and it breaks every INR order.
     *
     * Rounded to **nearest**, matching p2p's own `roundAmount` (`Math.round(amount * 1e6 / 1e4) /
     * 1e2`). A "safer" ceiling would put us a paisa off what their merchants reconcile against on
     * half of all orders. Note this is the opposite of [build]'s DOWN rounding, which exists
     * because the Diamond re-derives USDC from the sell-side amount and floors it.
     */
    fun buildBuyIntent(payeeAddress: String, orderId: BigInteger, fiatAmount: Usdc6, currencyCode: String): String {
        require(payeeAddress.isNotBlank()) { "payee address must not be blank" }
        require(fiatAmount.micros.signum() > 0) { "fiat amount must be positive" }
        return buildString {
            append("upi://pay?")
            append("pa=").append(percentEncode(payeeAddress))
            append("&tr=").append(percentEncode(orderId.toString()))
            append("&am=").append(twoDecimalAmount(fiatAmount))
            append("&cu=").append(percentEncode(currencyCode))
        }
    }

    /**
     * Half-up to two decimals, always with both of them — a bare `539.2` is as malformed to a
     * payment app as the full 6dp value.
     */
    fun twoDecimalAmount(fiatAmount: Usdc6): String {
        val hundredths =
            (fiatAmount.micros + HALF_HUNDREDTH_IN_MICROS)
                .divide(MICROS_PER_HUNDREDTH)
                .toLong()
        val fraction = (hundredths % HUNDRED).toString().padStart(2, '0')
        return "${hundredths / HUNDRED}.$fraction"
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

    private const val HUNDRED = 100L
    private const val MICROS_PER_HUNDREDTH_VALUE = 10_000L
    private const val HALF_HUNDREDTH_VALUE = 5_000L
    private val MICROS_PER_HUNDREDTH: BigInteger = bigIntegerValueOf(MICROS_PER_HUNDREDTH_VALUE)
    private val HALF_HUNDREDTH_IN_MICROS: BigInteger = bigIntegerValueOf(HALF_HUNDREDTH_VALUE)

    private const val HEX_NIBBLE = 4
    private const val HEX_MASK = 0xF
    private val HEX = "0123456789ABCDEF".toCharArray()
}
