// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.EmvQr
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnrampIntentAmountTest {
    @Test
    fun `reads the amount a upi intent will charge`() =
        runTest {
            assertEquals(
                Usdc6.ofMicros(105_000_000),
                OnrampIntentAmount.declaredAmount(CurrencyCode.Inr, upi("105.00")),
            )
        }

    @Test
    fun `a two decimal rounding of the order amount is not a mismatch`() =
        runTest {
            // The order is 104.999902; the intent legitimately rounds to 105.00.
            assertFalse(
                OnrampIntentAmount.disagreesWith(CurrencyCode.Inr, upi("105.00"), Usdc6.ofMicros(104_999_902)),
            )
        }

    /**
     * The failure this guard exists for: a payload carrying raw micros instead of whole units.
     * A payment app reports that as a limit breach, which reads like the bank refusing the user.
     */
    @Test
    fun `a payload carrying raw micros is a mismatch on every corridor`() =
        runTest {
            assertTrue(
                OnrampIntentAmount.disagreesWith(CurrencyCode.Inr, upi("104999902"), Usdc6.ofMicros(104_999_902)),
            )
            assertTrue(
                OnrampIntentAmount.disagreesWith(CurrencyCode.Brl, pix("25000000"), Usdc6.ofMicros(25_000_000)),
            )
            assertTrue(
                OnrampIntentAmount.disagreesWith(CurrencyCode.Idr, qris("16000000000"), Usdc6.ofMicros(16_000_000_000)),
            )
        }

    /** PIX and QRIS are EMV payloads, not URIs: the guard must read them through their own rails. */
    @Test
    fun `the emv corridors are read and agree with a matching order`() =
        runTest {
            assertEquals(
                Usdc6.ofMicros(25_000_000),
                OnrampIntentAmount.declaredAmount(CurrencyCode.Brl, pix("25.00")),
            )
            assertEquals(
                Usdc6.ofMicros(16_000_000_000),
                OnrampIntentAmount.declaredAmount(CurrencyCode.Idr, qris("16000")),
            )
            assertFalse(OnrampIntentAmount.disagreesWith(CurrencyCode.Brl, pix("25.00"), Usdc6.ofMicros(25_000_000)))
            assertFalse(
                OnrampIntentAmount.disagreesWith(CurrencyCode.Idr, qris("16000"), Usdc6.ofMicros(16_000_000_000)),
            )
        }

    /** A corridor whose payload is read against the wrong rail must not silently pass as "agrees". */
    @Test
    fun `a payload that does not parse for the order currency declares nothing`() =
        runTest {
            assertNull(OnrampIntentAmount.declaredAmount(CurrencyCode.Brl, upi("105.00")))
            assertNull(OnrampIntentAmount.declaredAmount(CurrencyCode.Inr, pix("25.00")))
        }

    @Test
    fun `an instruction with no declared amount is not a mismatch`() =
        runTest {
            val noAmount = OnrampPaymentInstruction.Upi("m@upi", "upi://pay?pa=m@upi&cu=INR&tr=1", "")

            assertNull(OnrampIntentAmount.declaredAmount(CurrencyCode.Inr, noAmount))
            assertFalse(OnrampIntentAmount.disagreesWith(CurrencyCode.Inr, noAmount, Usdc6.ofMicros(104_999_902)))
            assertNull(
                OnrampIntentAmount.declaredAmount(CurrencyCode.Inr, OnrampPaymentInstruction.Plain("m@upi")),
            )
        }

    private fun upi(amount: String) =
        OnrampPaymentInstruction.Upi(
            address = "m@upi",
            intentUrl = "upi://pay?pa=m@upi&pn=Merchant&am=$amount&cu=INR&tr=659007",
            amount = amount,
        )

    private fun pix(amount: String) =
        OnrampPaymentInstruction.Qr(
            emv(currency = "986", country = "BR", amount = amount).let { it + "6304" + EmvQr.calculateCrc16(it) },
        )

    private fun qris(amount: String) =
        OnrampPaymentInstruction.Qr(emv(currency = "360", country = "ID", amount = amount))

    private fun emv(
        currency: String,
        country: String,
        amount: String,
    ) = tlv("00", "01") + tlv("53", currency) + tlv("58", country) + tlv("59", "MERCHANT") + tlv("54", amount)

    private fun tlv(
        tag: String,
        value: String,
    ) = tag + value.length.toString().padStart(2, '0') + value
}
