// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.1.7 (`test/qr-parsers/idr.test.ts`). Inputs are
 * lifted verbatim from the SDK suite; a divergence means a QRIS merchant QR the SDK accepts would
 * be rejected here (or vice-versa) after `placeOrder` is already on-chain. Keep in lockstep.
 */
class QrisQrParserTest {
    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    private fun qris(vararg fields: String): String =
        tlv("00", "01") + tlv("53", "360") + tlv("58", "ID") + fields.joinToString("")

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(QrisQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(QrisQrParser.parse(qr)).error

    @Test
    fun `parses merchant name only`() {
        val qr = qris(tlv("59", "ACME STORE"))
        val data = parsed(qr)
        assertEquals("ACME STORE", data.paymentAddress)
        assertNull(data.fiatAmount)
    }

    @Test
    fun `parses merchant with amount`() {
        val qr = qris(tlv("59", "ACME"), tlv("54", "16000"))
        val data = parsed(qr)
        assertEquals("ACME", data.paymentAddress)
        assertEquals(BigDecimal("16000"), data.fiatAmount)
    }

    @Test
    fun `trims whitespace`() {
        assertEquals("ACME", parsed("  " + qris(tlv("59", "ACME")) + "  ").paymentAddress)
    }

    @Test
    fun `empty or whitespace is EmptyQr`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }

    @Test
    fun `missing merchant name tag 59 is MissingPaymentAddress`() {
        assertIs<PaymentQrError.MissingPaymentAddress>(error(qris(tlv("54", "100"))))
    }

    @Test
    fun `non-parseable amount is InvalidAmount`() {
        val qr = qris(tlv("59", "ACME"), tlv("54", "xyz"))
        assertIs<PaymentQrError.InvalidAmount>(error(qr))
    }

    @Test
    fun `rejects a non-IDR EMV payload`() {
        val qr = tlv("00", "01") + tlv("53", "986") + tlv("58", "BR") + tlv("59", "ACME")
        assertIs<PaymentQrError.InvalidFormat>(error(qr))
    }

    // -- real-world EMVCo MPM fixtures from the SDK suite --------------------------------------

    @Test
    fun `parses a dynamic QRIS with amount`() {
        val qr =
            "00020101021226570016ID.CO.SAMPLE.WWW01189360091400001234560211000000000015204482953033605405500005802ID" +
                "5918WARUNG CONTOH SATU601212345 KOTA A"
        val data = parsed(qr)
        assertEquals("WARUNG CONTOH SATU", data.paymentAddress)
        assertEquals(BigDecimal("50000"), data.fiatAmount)
    }

    @Test
    fun `parses a static QRIS without amount`() {
        val qr =
            "00020101021126430017ID.CO.EXAMPLE.WWW01189360098800009876545204541153033605802ID" +
                "5915TOKO CONTOH DUA600905 KOTA B"
        val data = parsed(qr)
        assertEquals("TOKO CONTOH DUA", data.paymentAddress)
        assertNull(data.fiatAmount)
    }
}
