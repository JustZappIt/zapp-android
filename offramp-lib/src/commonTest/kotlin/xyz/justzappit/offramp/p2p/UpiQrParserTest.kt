// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.1.7
 * (`test/qr-parsers/inr.test.ts` and `country/currencies/inr.ts validateUPIId`).
 * If a case here diverges from the SDK, a merchant QR that the SDK would accept will be
 * silently rejected by us — or worse, we'd broadcast `placeOrder` then fail to encrypt the
 * UPI for a handle the SDK can't process. Keep these in lockstep.
 */
class UpiQrParserTest {
    // -- validateUpiId — strict form (matches `country/currencies/inr.ts`) ----------------------

    @Test
    fun `validateUpiId accepts canonical merchant handles`() {
        assertTrue(UpiQrParser.validateUpiId("john@paytm"))
        assertTrue(UpiQrParser.validateUpiId("user@ybl"))
        assertTrue(UpiQrParser.validateUpiId("user.name@ybl"))
        assertTrue(UpiQrParser.validateUpiId("user_name@axisbank"))
        assertTrue(UpiQrParser.validateUpiId("user-name@hdfcbank"))
    }

    @Test
    fun `validateUpiId accepts phone-number handles and banks with digits`() {
        // Bank handle with digits (e.g. kotak811) is the canonical example in the SDK docs.
        // Our previous local regex `[A-Za-z]{2,64}` for the bank part silently rejected this.
        assertTrue(UpiQrParser.validateUpiId("8658404239@kotak811"))
        assertTrue(UpiQrParser.validateUpiId("9876543210@ybl"))
        assertTrue(UpiQrParser.validateUpiId("merchant1234@okhdfcbank"))
    }

    @Test
    fun `validateUpiId trims surrounding whitespace`() {
        assertTrue(UpiQrParser.validateUpiId("  merchant@upi  "))
    }

    @Test
    fun `validateUpiId rejects malformed input`() {
        assertFalse(UpiQrParser.validateUpiId(""))
        assertFalse(UpiQrParser.validateUpiId("   "))
        assertFalse(UpiQrParser.validateUpiId("nodomain"))
        assertFalse(UpiQrParser.validateUpiId("@nobank"))
        assertFalse(UpiQrParser.validateUpiId("user@"))
        assertFalse(UpiQrParser.validateUpiId("user@@bank"))
        assertFalse(UpiQrParser.validateUpiId("a@b")) // local part below 2-char minimum
        assertFalse(UpiQrParser.validateUpiId("user@b")) // bank part below 2-char minimum
        assertFalse(UpiQrParser.validateUpiId("us er@bank")) // space inside local
        assertFalse(UpiQrParser.validateUpiId("user@bank.com")) // dot in bank — strict form rejects
    }

    // -- parseQr — full URI form -----------------------------------------------------------------

    @Test
    fun `parseQr extracts payment address from upi pay URI`() {
        val result = UpiQrParser.parseQr("upi://pay?pa=merchant@okaxis&pn=Merchant")
        val parsed = assertIs<UpiQrParseResult.Success>(result).parsed
        assertEquals("merchant@okaxis", parsed.paymentAddress)
        assertNull(parsed.fiatAmount)
    }

    @Test
    fun `parseQr extracts amount when am is present`() {
        val result = UpiQrParser.parseQr("upi://pay?pa=merchant@okaxis&am=800")
        val parsed = assertIs<UpiQrParseResult.Success>(result).parsed
        assertEquals(BigDecimal("800"), parsed.fiatAmount)
    }

    @Test
    fun `parseQr accepts bare query string without upi pay prefix`() {
        val result = UpiQrParser.parseQr("pa=user.name@bank&am=100.5")
        val parsed = assertIs<UpiQrParseResult.Success>(result).parsed
        assertEquals("user.name@bank", parsed.paymentAddress)
        assertEquals(BigDecimal("100.5"), parsed.fiatAmount)
    }

    @Test
    fun `parseQr trims surrounding whitespace`() {
        val result = UpiQrParser.parseQr("  upi://pay?pa=foo@bar  ")
        val parsed = assertIs<UpiQrParseResult.Success>(result).parsed
        assertEquals("foo@bar", parsed.paymentAddress)
    }

    @Test
    fun `parseQr returns EmptyQr for blank input`() {
        val empty = UpiQrParser.parseQr("")
        assertEquals(UpiQrError.EmptyQr, assertIs<UpiQrParseResult.Failure>(empty).error)

        val whitespace = UpiQrParser.parseQr("   ")
        assertEquals(UpiQrError.EmptyQr, assertIs<UpiQrParseResult.Failure>(whitespace).error)
    }

    @Test
    fun `parseQr returns MissingPaymentAddress when pa is absent`() {
        val result = UpiQrParser.parseQr("upi://pay?pn=Merchant&am=100")
        assertEquals(
            UpiQrError.MissingPaymentAddress,
            assertIs<UpiQrParseResult.Failure>(result).error,
        )
    }

    @Test
    fun `parseQr returns InvalidUpiId for malformed pa`() {
        // URL-encoded space → not a valid UPI handle once decoded.
        val result = UpiQrParser.parseQr("upi://pay?pa=not%20a%20upi%20id")
        val err = assertIs<UpiQrParseResult.Failure>(result).error
        assertIs<UpiQrError.InvalidUpiId>(err)
    }

    @Test
    fun `parseQr returns InvalidAmount for non-numeric am`() {
        val result = UpiQrParser.parseQr("upi://pay?pa=m@bb&am=notanumber")
        val err = assertIs<UpiQrParseResult.Failure>(result).error
        assertIs<UpiQrError.InvalidAmount>(err)
    }

    @Test
    fun `parseQr returns InvalidAmount for zero or negative am`() {
        val zero = UpiQrParser.parseQr("upi://pay?pa=m@bb&am=0")
        assertIs<UpiQrError.InvalidAmount>(assertIs<UpiQrParseResult.Failure>(zero).error)

        val negative = UpiQrParser.parseQr("upi://pay?pa=m@bb&am=-50")
        assertIs<UpiQrError.InvalidAmount>(assertIs<UpiQrParseResult.Failure>(negative).error)
    }

    @Test
    fun `parseQr rejects a contradictory currency`() {
        val result = UpiQrParser.parseQr("upi://pay?pa=merchant@okaxis&am=10&cu=USD")
        assertIs<UpiQrError.InvalidCurrency>(assertIs<UpiQrParseResult.Failure>(result).error)
    }

    // -- parseQr — real-world fixtures from the SDK test suite ----------------------------------

    @Test
    fun `parseQr handles a real merchant QR with full query string`() {
        val qr =
            "upi://pay?pa=test.merchant@examplebank&pn=Test%20Merchant&mc=5411" +
                "&tr=TXN0000001&tn=Payment&am=250.00&cu=INR"
        val parsed = assertIs<UpiQrParseResult.Success>(UpiQrParser.parseQr(qr)).parsed
        assertEquals("test.merchant@examplebank", parsed.paymentAddress)
        assertEquals(BigDecimal("250.00"), parsed.fiatAmount)
    }

    @Test
    fun `parseQr handles a personal QR with no amount`() {
        val qr = "upi://pay?pa=testuser@examplebank&pn=Test%20User"
        val parsed = assertIs<UpiQrParseResult.Success>(UpiQrParser.parseQr(qr)).parsed
        assertEquals("testuser@examplebank", parsed.paymentAddress)
        assertNull(parsed.fiatAmount)
    }

    @Test
    fun `parseQr handles long merchant handle with mixed alphanumerics`() {
        val qr = "upi://pay?pa=examplemerchantqr0000000000000000@examplebank&pn=Example&am=1500&cu=INR"
        val parsed = assertIs<UpiQrParseResult.Success>(UpiQrParser.parseQr(qr)).parsed
        assertEquals("examplemerchantqr0000000000000000@examplebank", parsed.paymentAddress)
        assertEquals(BigDecimal("1500"), parsed.fiatAmount)
    }

    @Test
    fun `parseQr handles small-decimal amount`() {
        val qr = "upi://pay?pa=sample.store@examplebank&pn=Sample%20Store&am=99.99&cu=INR"
        val parsed = assertIs<UpiQrParseResult.Success>(UpiQrParser.parseQr(qr)).parsed
        assertEquals(BigDecimal("99.99"), parsed.fiatAmount)
    }

    @Test
    fun `parseQr accepts bank handles with dots and dashes in lenient form`() {
        // The QR-form regex is intentionally looser than the typed-form validator.
        // Banks like `paytm.bank` or `state-bank` would be rejected by strict typing but the
        // SDK accepts them in scanned QRs, so we must too.
        val withDot = "upi://pay?pa=merchant@paytm.bank"
        val parsedDot = assertIs<UpiQrParseResult.Success>(UpiQrParser.parseQr(withDot)).parsed
        assertEquals("merchant@paytm.bank", parsedDot.paymentAddress)

        val withDash = "upi://pay?pa=merchant@state-bank"
        val parsedDash = assertIs<UpiQrParseResult.Success>(UpiQrParser.parseQr(withDash)).parsed
        assertEquals("merchant@state-bank", parsedDash.paymentAddress)
    }
}
