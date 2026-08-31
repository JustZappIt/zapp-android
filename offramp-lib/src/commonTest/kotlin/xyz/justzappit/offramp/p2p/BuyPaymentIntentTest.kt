// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.bigIntegerValueOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `am=` field is the single most breakable thing on the buy path: fiat is 6dp internally, and
 * a payment app rejects anything but two decimals with an error that reads to the user as a failed
 * payment rather than a malformed request.
 */
class BuyPaymentIntentTest {
    @Test
    fun `six decimal fiat becomes exactly two`() {
        // The real shape of a quote: 539.25888 rupees, which PhonePe refuses verbatim.
        assertEquals("539.26", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(539_258_880L)))
    }

    @Test
    fun `rounding is to nearest, matching p2p's own roundAmount`() {
        // Half-up at the paisa, both directions — a ceiling would sit a paisa off what the
        // merchant reconciles against on roughly half of all orders.
        assertEquals("1.00", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(999_999L)))
        assertEquals("1.00", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(1_004_999L)))
        assertEquals("1.01", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(1_005_000L)))
        assertEquals("0.99", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(994_999L)))
    }

    @Test
    fun `a trailing zero is never dropped`() {
        // "539.2" is as malformed to a payment app as the full six decimals.
        assertEquals("539.20", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(539_200_000L)))
        assertEquals("100.00", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(100_000_000L)))
        assertEquals("0.05", UpiPayUri.twoDecimalAmount(Usdc6.ofMicros(50_000L)))
    }

    @Test
    fun `the intent carries the order id as the merchant's reference`() {
        val uri =
            UpiPayUri.buildBuyIntent(
                payeeAddress = "merchant@okhdfcbank",
                orderId = bigIntegerValueOf(123_456L),
                fiatAmount = Usdc6.ofMicros(539_258_880L),
                currencyCode = CurrencyCode.Inr.code,
            )
        // tr= is what lets the merchant match the payment to the order; without it the money
        // arrives against nothing.
        assertEquals("upi://pay?pa=merchant@okhdfcbank&tr=123456&am=539.26&cu=INR", uri)
    }
}
