// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.2.4 (`test/qr-parsers/ven.test.ts`). A Venezuelan
 * Pago Móvil QR is an opaque base64 payload only the banks can decipher, followed by `?` and routing
 * metadata; the whole raw string is the payment address. Keep in lockstep.
 */
class PagoMovilQrParserTest {
    // Decodes to "the venezuelan qr is a base64 encrypted string that only banks can decipher".
    private val venBase64 =
        "dGhlIHZlbmV6dWVsYW4gcXIgaXMgYSBiYXNlNjQgZW5jcnlwdGVkIHN0cmluZyB0aGF0IG9ubHkgYmFua3MgY2FuIGRlY2lwaGVy"

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(PagoMovilQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(PagoMovilQrParser.parse(qr)).error

    @Test
    fun `returns the full QR string as payment address`() {
        val qr = "SGVsbG9Xb3JsZA==?param=1"
        val data = parsed(qr)
        assertEquals(qr, data.paymentAddress)
        assertNull(data.fiatAmount)
    }

    @Test
    fun `trims whitespace but preserves the trimmed QR as payment address`() {
        val qr = "dGVzdA==?x=y"
        assertEquals(qr, parsed("  $qr  ").paymentAddress)
    }

    @Test
    fun `parses a real-world PagoMovil QR routed to a bank`() {
        val qr = "$venBase64?bank=BANCO_A"
        assertEquals(qr, parsed(qr).paymentAddress)
    }

    @Test
    fun `empty or whitespace is EmptyQr`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }

    @Test
    fun `no question-mark separator is InvalidFormat`() {
        assertIs<PaymentQrError.InvalidFormat>(error("SGVsbG8="))
    }

    @Test
    fun `non-base64 payload is InvalidFormat`() {
        assertIs<PaymentQrError.InvalidFormat>(error("not base64!@#?x=y"))
    }

    @Test
    fun `empty payload before question-mark is InvalidFormat`() {
        assertIs<PaymentQrError.InvalidFormat>(error("?x=y"))
    }
}
