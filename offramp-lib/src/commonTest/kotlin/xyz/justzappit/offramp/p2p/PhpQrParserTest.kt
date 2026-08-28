// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Cross-language parity against `@p2pdotme/sdk` v1.2.21 (`test/qr-parsers/php.test.ts`). */
class PhpQrParserTest {
    private val sample =
        "00020101021127830012com.p2pqrpay0111GXCHPHM2XXX02081234567803150000000000000000417TESTTESTTESTTEST1" +
            "5204601653036085802PH5909TEST SHOP6006Manila61041000630476A9"

    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    private fun withCrc(data: String): String = data + "6304" + EmvQr.calculateCrc16(data)

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(PhpQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(PhpQrParser.parse(qr)).error

    @Test
    fun `QR Ph sample returns the raw payload verbatim and no amount`() {
        assertEquals(sample, parsed(sample).paymentAddress)
        assertNull(parsed(sample).fiatAmount)
    }

    @Test
    fun `tag 54 becomes the fiat amount`() {
        val qr = withCrc("000201" + tlv("53", "608") + tlv("54", "1500") + tlv("58", "PH") + tlv("59", "JUAN D."))
        assertEquals(0, parsed(qr).fiatAmount?.compareTo(BigDecimal("1500")))
    }

    @Test
    fun `a wallet-proprietary QR without the PH country and 608 currency tags is rejected`() {
        assertIs<PaymentQrError.InvalidFormat>(error(withCrc("000201" + tlv("53", "608") + tlv("58", "ID"))))
        assertIs<PaymentQrError.InvalidFormat>(error(withCrc("000201" + tlv("53", "360") + tlv("58", "PH"))))
        assertIs<PaymentQrError.InvalidFormat>(error(withCrc("000201" + tlv("53", "608"))))
    }

    @Test
    fun `a corrupted CRC is rejected`() {
        assertIs<PaymentQrError.InvalidChecksum>(error(sample.replace("630476A9", "6304FFFF")))
    }

    @Test
    fun `empty and blank payloads are rejected`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }
}
