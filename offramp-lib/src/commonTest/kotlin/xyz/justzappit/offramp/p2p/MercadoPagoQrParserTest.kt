// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.2.4 (`test/qr-parsers/ars.test.ts`). Inputs are
 * lifted verbatim from the SDK suite; a divergence means a MercadoPago QR the SDK accepts would be
 * rejected here (or vice-versa) after `placeOrder` is already on-chain. Keep in lockstep.
 */
class MercadoPagoQrParserTest {
    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    private fun withCrc(inner: String): String = inner + "6304" + EmvQr.calculateCrc16(inner)

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(MercadoPagoQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(MercadoPagoQrParser.parse(qr)).error

    @Test
    fun `parses a valid ARS QR with merchant name`() {
        val inner = tlv("00", "01") + tlv("53", "032") + tlv("58", "AR") + tlv("59", "MERCADOPAGO")
        val data = parsed(withCrc(inner))
        assertEquals("MERCADOPAGO", data.paymentAddress)
        assertNull(data.fiatAmount)
    }

    @Test
    fun `falls back to Unknown when merchant tag 59 is missing`() {
        val inner = tlv("00", "01") + tlv("53", "032") + tlv("58", "AR")
        assertEquals("Unknown", parsed(withCrc(inner)).paymentAddress)
    }

    @Test
    fun `accepts QR with only the AR country marker`() {
        val inner = tlv("00", "01") + tlv("58", "AR") + tlv("59", "SHOP")
        assertEquals("SHOP", parsed(withCrc(inner)).paymentAddress)
    }

    @Test
    fun `empty or whitespace is EmptyQr`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }

    @Test
    fun `neither ARS nor AR marker is InvalidFormat`() {
        val inner = tlv("00", "01") + tlv("59", "SHOP")
        assertIs<PaymentQrError.InvalidFormat>(error(withCrc(inner)))
    }

    @Test
    fun `CRC mismatch is InvalidChecksum`() {
        val inner = tlv("00", "01") + tlv("58", "AR") + tlv("59", "SHOP")
        assertIs<PaymentQrError.InvalidChecksum>(error(inner + "6304FFFF"))
    }

    // -- real-world MercadoPago EMVCo fixtures from the SDK suite ------------------------------

    @Test
    fun `parses a MercadoPago store QR with amount`() {
        val qr =
            "00020101021226410016com.mercadolibre01090000000000204000052045411530303254072500.00" +
                "5802AR5912COMERCIO UNO6008CIUDAD A63046A3A"
        assertEquals("COMERCIO UNO", parsed(qr).paymentAddress)
    }

    @Test
    fun `parses a MercadoPago personal QR without amount`() {
        val qr =
            "00020101021126410016com.mercadolibre0109111111111020400005204000053030325802AR" +
                "5911PERSONA UNO6008CIUDAD B630467A9"
        assertEquals("PERSONA UNO", parsed(qr).paymentAddress)
    }
}
