// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.2.21 (`test/qr-parsers/cup.test.ts`). Fixtures
 * are the SDK's own; the card and phone are fabricated.
 */
class CupQrParserTest {
    private val noAmount = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,58555555,"
    private val expectedAddress = "58555555|9204959800000000"

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(CupQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(CupQrParser.parse(qr)).error

    @Test
    fun `an empty trailing amount field yields phone pipe card and no amount`() {
        assertEquals(expectedAddress, parsed(noAmount).paymentAddress)
        assertNull(parsed(noAmount).fiatAmount)
    }

    @Test
    fun `the amount is parsed when present`() {
        val qr = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,58555555,800"
        assertEquals(0, parsed(qr).fiatAmount?.compareTo(BigDecimal("800")))
    }

    @Test
    fun `the trailing amount separator is optional`() {
        val qr = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,58555555"
        assertEquals(expectedAddress, parsed(qr).paymentAddress)
    }

    @Test
    fun `extra trailing fields are ignored`() {
        val qr = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,58555555,800,CUP"
        assertEquals(expectedAddress, parsed(qr).paymentAddress)
        assertEquals(0, parsed(qr).fiatAmount?.compareTo(BigDecimal("800")))
    }

    @Test
    fun `a 53 country code and spaces normalise to the same address`() {
        assertEquals(
            expectedAddress,
            parsed("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,+53 58555555,").paymentAddress,
        )
        assertEquals(
            expectedAddress,
            parsed("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204 9598 0000 0000,58555555,").paymentAddress,
        )
    }

    @Test
    fun `other operation types share the field layout`() {
        assertEquals(expectedAddress, parsed("TRANSFERMOVIL_ETECSA,PAGO,9204959800000000,58555555,").paymentAddress)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(expectedAddress, parsed("  $noAmount  ").paymentAddress)
    }

    @Test
    fun `a foreign prefix is rejected`() {
        assertIs<PaymentQrError.InvalidFormat>(error("ENZONA,TRANSFERENCIA,9204959800000000,58555555,"))
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }

    @Test
    fun `a truncated record or a malformed card or phone is rejected`() {
        assertIs<PaymentQrError.MissingPaymentAddress>(error("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000"))
        assertIs<PaymentQrError.InvalidPaymentAddress>(error("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,92049598,58555555,"))
        assertIs<PaymentQrError.InvalidPaymentAddress>(
            error("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,92049598000000AB,58555555,"),
        )
        assertIs<PaymentQrError.InvalidPaymentAddress>(
            error("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,555,"),
        )
    }

    @Test
    fun `a non-positive amount is rejected`() {
        assertIs<PaymentQrError.InvalidAmount>(error("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,58555555,0"))
    }
}
