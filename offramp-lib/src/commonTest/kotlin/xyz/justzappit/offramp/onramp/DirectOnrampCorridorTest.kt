// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A resumed order has no caller left to say which corridor it belongs to, so the driver reads it
 * back off the order's own `bytes32` currency word. Round-tripping that word is what stands between
 * a resumed INR order and a payment intent built in the wrong currency.
 */
class DirectOnrampCorridorTest {
    private val orderId: BigInteger = bigIntegerValueOf(7)
    private val fiat = Usdc6.ofMicros(445_000_000)

    /** A whole EMVCo QR string, which is what PEN/PHP/BOB put in the "address" field. */
    private val emvcoPayload = "00020101021226580014BR.GOV.BCB.PIX0136abc-def5204000053039865802BR6304A1B2"
    private val vpa = "merchant@upi"

    @Test
    fun `every corridor's bytes32 word round-trips back to its own code`() {
        CurrencyCode.entries.forEach { currency ->
            val word = "0x" + AbiEncoder.bytes32String(currency.code).value.toHex()
            assertEquals(currency, corridorFromBytes32(word), "round trip failed for ${currency.code}")
        }
    }

    @Test
    fun `the INR word is the one the chain really stores`() {
        // Read from a live order on Base mainnet: "INR", NUL-padded to 32 bytes.
        assertEquals(
            CurrencyCode.Inr,
            corridorFromBytes32("0x494e520000000000000000000000000000000000000000000000000000000000"),
        )
    }

    @Test
    fun `an undecodable corridor is paid verbatim, never as a UPI intent`() {
        // ☠ The whole point of decoding to null. INR is the one branch that wraps the handle in a
        // upi:// intent and stamps a currency on it; every EMVCo corridor's "handle" is a complete
        // QR payload. Defaulting an unreadable order to INR pays a QR string over UPI.
        val instruction = paymentInstructionFor(emvcoPayload, orderId, fiat, currency = null)

        val plain = assertIs<OnrampPaymentInstruction.Plain>(instruction)
        assertEquals(emvcoPayload, plain.address)
    }

    @Test
    fun `each corridor is paid over the rail it actually uses`() {
        assertIs<OnrampPaymentInstruction.Upi>(
            paymentInstructionFor(vpa, orderId, fiat, CurrencyCode.Inr),
        )
        // A complete EMVCo payload: a QR, never a string on screen and never a upi:// intent.
        listOf(CurrencyCode.Pen, CurrencyCode.Php, CurrencyCode.Bob).forEach { currency ->
            assertIs<OnrampPaymentInstruction.Qr>(
                paymentInstructionFor(emvcoPayload, orderId, fiat, currency),
                "${currency.code} must render as a QR",
            )
        }
        // Everything else is the merchant's handle, verbatim.
        assertIs<OnrampPaymentInstruction.Plain>(
            paymentInstructionFor(vpa, orderId, fiat, CurrencyCode.Ngn),
        )
    }

    @Test
    fun `a corridor this app does not serve decodes to nothing, not to a default`() {
        // MEX is a real p2p market that Zapp deliberately does not carry.
        val word = "0x" + AbiEncoder.bytes32String("MEX").value.toHex()
        assertNull(corridorFromBytes32(word))
    }
}
