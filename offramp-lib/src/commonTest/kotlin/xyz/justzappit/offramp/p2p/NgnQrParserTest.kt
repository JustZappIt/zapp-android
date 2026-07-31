// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.2.4 (`test/qr-parsers/ngn.test.ts`). Covers both
 * NIBSS NQR (EMVCo) and the SPD account format. Inputs lifted verbatim from the SDK suite. Keep in
 * lockstep.
 */
class NgnQrParserTest {
    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    private fun withCrc(inner: String): String = inner + "6304" + EmvQr.calculateCrc16(inner)

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(NgnQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(NgnQrParser.parse(qr)).error

    private val nibssMerchantInfo = tlv("00", "NG.COM.NIBSSPLC.QR") + tlv("01", "S000000000000")

    @Test
    fun `parses a static NQR with merchant name and no amount`() {
        val inner =
            tlv("00", "01") + tlv("01", "11") + tlv("26", nibssMerchantInfo) +
                tlv("52", "0000") + tlv("53", "566") + tlv("58", "NG") +
                tlv("59", "ACME STORE NG") + tlv("60", "Lagos")
        val data = parsed(withCrc(inner))
        assertEquals("ACME STORE NG", data.paymentAddress)
        assertNull(data.fiatAmount)
    }

    @Test
    fun `parses a dynamic NQR with amount`() {
        val inner =
            tlv("00", "01") + tlv("01", "12") + tlv("26", nibssMerchantInfo) +
                tlv("52", "0000") + tlv("53", "566") + tlv("54", "15000.00") +
                tlv("58", "NG") + tlv("59", "MERCHANT ONE") + tlv("60", "Lagos")
        val data = parsed(withCrc(inner))
        assertEquals("MERCHANT ONE", data.paymentAddress)
        assertEquals(BigDecimal("15000.00"), data.fiatAmount)
    }

    @Test
    fun `accepts NQR with only the NG country marker`() {
        val inner = tlv("00", "01") + tlv("01", "11") + tlv("58", "NG") + tlv("59", "SHOP NG")
        assertEquals("SHOP NG", parsed(withCrc(inner)).paymentAddress)
    }

    @Test
    fun `CRC mismatch is InvalidChecksum`() {
        val inner = tlv("00", "01") + tlv("53", "566") + tlv("58", "NG") + tlv("59", "M")
        assertIs<PaymentQrError.InvalidChecksum>(error(inner + "6304FFFF"))
    }

    @Test
    fun `no Nigerian marker is InvalidFormat`() {
        val inner = tlv("00", "01") + tlv("53", "840") + tlv("58", "US") + tlv("59", "SHOP")
        assertIs<PaymentQrError.InvalidFormat>(error(withCrc(inner)))
    }

    @Test
    fun `missing merchant name is MissingPaymentAddress`() {
        val inner = tlv("00", "01") + tlv("53", "566") + tlv("58", "NG")
        assertIs<PaymentQrError.MissingPaymentAddress>(error(withCrc(inner)))
    }

    @Test
    fun `unparseable NQR amount is InvalidAmount`() {
        val inner =
            tlv("00", "01") + tlv("53", "566") + tlv("54", "abc") + tlv("58", "NG") + tlv("59", "SHOP")
        assertIs<PaymentQrError.InvalidAmount>(error(withCrc(inner)))
    }

    // -- SPD (Czech Short Payment Descriptor) --------------------------------------------------

    @Test
    fun `parses an SPD QR with account amount and message`() {
        val data = parsed("SPD*1.0*ACC:1234567890*AM:40,000.00*MSG:Test*")
        assertEquals("1234567890", data.paymentAddress)
        assertEquals(BigDecimal("40000.00"), data.fiatAmount)
    }

    @Test
    fun `parses an SPD QR with only account`() {
        val data = parsed("SPD*1.0*ACC:1234567890*")
        assertEquals("1234567890", data.paymentAddress)
        assertNull(data.fiatAmount)
    }

    @Test
    fun `SPD missing ACC field is MissingPaymentAddress`() {
        assertIs<PaymentQrError.MissingPaymentAddress>(error("SPD*1.0*AM:1000*"))
    }

    @Test
    fun `unparseable SPD amount is InvalidAmount`() {
        assertIs<PaymentQrError.InvalidAmount>(error("SPD*1.0*ACC:1234567890*AM:notanumber*"))
    }

    @Test
    fun `empty or whitespace is EmptyQr`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }

    @Test
    fun `non-TLV and non-SPD data is InvalidFormat`() {
        assertIs<PaymentQrError.InvalidFormat>(error("hello world"))
    }
}
