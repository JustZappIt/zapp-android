// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Cross-language parity against `@p2pdotme/sdk` v1.2.21 (`test/qr-parsers/pen.test.ts`). */
class PenQrParserTest {
    private val sample =
        "0002010102113932acfba6cb922753c690f09280f365d7a25204561153036045802PE5906YAPERO6004Lima6304ECE9"

    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    private fun withCrc(data: String): String = data + "6304" + EmvQr.calculateCrc16(data)

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(PenQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(PenQrParser.parse(qr)).error

    @Test
    fun `real Yape sample returns the raw payload verbatim and no amount`() {
        assertEquals(sample, parsed(sample).paymentAddress)
        assertNull(parsed(sample).fiatAmount)
    }

    @Test
    fun `tag 54 becomes the fiat amount`() {
        val qr = withCrc("000201" + tlv("53", "604") + tlv("54", "1500") + tlv("58", "PE") + tlv("59", "YAPERO"))
        assertEquals(qr, parsed(qr).paymentAddress)
        assertEquals(0, parsed(qr).fiatAmount?.compareTo(BigDecimal("1500")))
    }

    @Test
    fun `surrounding whitespace is trimmed before the CRC is checked`() {
        assertEquals(sample, parsed("  $sample  ").paymentAddress)
    }

    @Test
    fun `a non-Peru currency tag is rejected`() {
        assertIs<PaymentQrError.InvalidFormat>(error(sample.replace("5303604", "5303840")))
    }

    @Test
    fun `a corrupted CRC is rejected, matching SELL upload`() {
        assertIs<PaymentQrError.InvalidChecksum>(error(sample.replace("6304ECE9", "6304FFFF")))
    }

    @Test
    fun `empty and blank payloads are rejected`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }
}
