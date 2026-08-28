// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Cross-language parity against `@p2pdotme/sdk` v1.2.21 (`test/qr-parsers/ecu.test.ts`). */
class EcuQrParserTest {
    private val sample = "https://pagar.deuna.app/demo/merchant?id=demomerchant123"

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(EcuQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(EcuQrParser.parse(qr)).error

    @Test
    fun `the id parameter is the payment address and there is never an amount`() {
        assertEquals("demomerchant123", parsed(sample).paymentAddress)
        assertNull(parsed(sample).fiatAmount)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("demomerchant123", parsed("  $sample  ").paymentAddress)
    }

    @Test
    fun `a URL without an id parameter is rejected`() {
        assertIs<PaymentQrError.MissingPaymentAddress>(error("https://pagar.deuna.app/demo/merchant"))
    }

    @Test
    fun `a lookalike host carrying an id is rejected`() {
        assertIs<PaymentQrError.InvalidFormat>(error("https://evil.example.com/merchant?id=abc"))
        assertIs<PaymentQrError.InvalidFormat>(error("https://pagar.deuna.app.evil.com/m?id=abc"))
        assertIs<PaymentQrError.InvalidFormat>(error("https://evil.com/?x=pagar.deuna.app&id=abc"))
    }

    @Test
    fun `userinfo cannot be used to spoof the host`() {
        assertIs<PaymentQrError.InvalidFormat>(error("https://pagar.deuna.app@evil.com/m?id=abc"))
    }

    @Test
    fun `the host comparison ignores case and an explicit port`() {
        val upper = "https://PAGAR.DEUNA.APP/demo/merchant?id=demomerchant123"
        val ported = "https://pagar.deuna.app:443/demo/merchant?id=demomerchant123"
        assertEquals("demomerchant123", parsed(upper).paymentAddress)
        assertEquals("demomerchant123", parsed(ported).paymentAddress)
    }

    @Test
    fun `a percent-escaped id is decoded`() {
        assertEquals("a b+c", parsed("https://pagar.deuna.app/m?id=a%20b%2Bc").paymentAddress)
    }

    @Test
    fun `a fragment is not read as part of the query`() {
        assertEquals("demomerchant123", parsed("$sample#section").paymentAddress)
    }

    @Test
    fun `non-URL and empty payloads are rejected`() {
        assertIs<PaymentQrError.InvalidFormat>(error("not a url"))
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }
}
