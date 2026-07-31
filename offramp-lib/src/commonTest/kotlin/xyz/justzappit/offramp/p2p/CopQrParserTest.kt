// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.2.4 (`test/qr-parsers/cop.test.ts`). Covers the
 * DIAN electronic-invoice text format and the Nequi / Bre-B EMVCo format. Synthetic fixtures — every
 * identifier is fabricated (real DIAN QRs carry PII). Keep in lockstep.
 */
class CopQrParserTest {
    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    /** COP has no CRC check, so a fabricated `6304ABCD` trailer is accepted. */
    private fun buildCopEmv(merchantName: String, extraTemplates: String = ""): String =
        tlv("00", "01") + tlv("01", "11") + extraTemplates +
            tlv("52", "0000") + tlv("53", "170") + tlv("58", "CO") + tlv("59", merchantName) + "6304ABCD"

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(CopQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(CopQrParser.parse(qr)).error

    @Test
    fun `DIAN newline invoice returns CUFE as payment address`() {
        val cufe = "0".repeat(96)
        val qr =
            "NumFac: TEST00000001\n" +
                "FecFac: 2024-01-01\n" +
                "HorFac: 00:00:00-05:00\n" +
                "NitFac: 900000000\n" +
                "DocAdq: 1000000000\n" +
                "ValFac: 100.00\n" +
                "ValIva: 19.00\n" +
                "ValTolFac: 119.00\n" +
                "CUFE: $cufe\n" +
                "https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey=$cufe"
        assertEquals(cufe, parsed(qr).paymentAddress)
    }

    @Test
    fun `DIAN comma-separated invoice returns lowercase Cufe as payment address`() {
        val cufe = "a".repeat(40)
        val qr =
            "NumFac: TEST00000002,FecFac:20240101000000,NitFac:900000001,DocAdq:1000000001," +
                "ValFac:0.00;ValIVA:0.00,ValOtrImp:0.00,ValFacImp:0.00,Cufe:$cufe"
        assertEquals(cufe, parsed(qr).paymentAddress)
    }

    @Test
    fun `DIAN marker without CUFE or NumFac is MissingPaymentAddress`() {
        assertIs<PaymentQrError.MissingPaymentAddress>(error("NitFac: 900000000\nValFac: 100.00"))
    }

    @Test
    fun `Nequi-shaped EMVCo QR extracts merchant name from tag 59`() {
        val qr = buildCopEmv("TEST MERCHANT", tlv("92", tlv("00", "co.com.nequi") + tlv("01", "P2P.NEQUI")))
        val data = parsed(qr)
        assertEquals("TEST MERCHANT", data.paymentAddress)
        assertNull(data.fiatAmount)
    }

    @Test
    fun `Bre-B-shaped EMVCo QR extracts merchant name from tag 59`() {
        val qr = buildCopEmv("testshop", tlv("26", tlv("00", "CO.COM.RBM.LLA") + tlv("05", "0000000000")))
        assertEquals("testshop", parsed(qr).paymentAddress)
    }

    @Test
    fun `empty or whitespace is EmptyQr`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }

    @Test
    fun `unrecognized payload is InvalidFormat`() {
        assertIs<PaymentQrError.InvalidFormat>(error("just some random text"))
    }
}
