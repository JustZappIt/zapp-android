// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.2.21 (`test/qr-parsers/bob.test.ts`), but
 * against the *composed* Scan & Pay path: the SDK's `parseQR` re-applies `validateBolivianQr` after
 * `parseBolivia` returns, so the EMVCo branch requires a real CRC here. The SDK's own unit test
 * calls the bare parser and so accepts its `6304ABCD` placeholder — that payload is rejected by
 * `parseQR`, and by this parser.
 */
class BobQrParserTest {
    private val bancoSol =
        "rqeunYVqZLSBH9wP9g9edc2eo8ywIMBYO4Hp6zkL7K/lvplzVgpBfA7UA7nH6aNP7wnaDJe41h4YBHYVo8VCaYpigvLPxmRdbIry" +
            "kn2IFuJUi+2fCfY2Do7EtQU11c8JyZ0C1L5KRe5I4E59r9zeghuVQUUNtgaSsZS+mqqVQ5z0EDqo21xVmLjD3PWVY/4LJpz9" +
            "Cn8aFSwGPVk7fUd9SUpCGV812+IK9K1fE2okI+rtKmyWANBFWCUyz3EE2pvoRjMh6EosPnGzU1cRDapU0ZcOnsZAryOrXQz7" +
            "d0WM/rn6OHm5rW+a5OVt93YqOqfNLXW2VYQPVbTg85+UlkQIpw==|07F204D5938E28075E5BF22340391EE1"
    private val bancoFie =
        "UGbUtEEepdB6Lu0ZjvDh5rCdCUUw9mc8i8+lV0amjuD94l//AN/b4sE1OkUqxb5MR2WwIAe8L97Ax6GEUc0EAcWk/gA/mqwmoLqd" +
            "UpJGzSqBFo+FcdjRevpIxNrkBj4L3IM6my02LUbDZUdpoeFzrQ/rJoPu/qtrrf+7JAw2GOSoOGl5jBS2IH6E11geLOs85G7h" +
            "LkSI8YmI39WbAFqL0mmt+B13CZg5owV2LO9Ul3v9KMbg0D90oL9jk39bxwzuYxAOe5AjoUb4WxdIEO05OaWG2H6St0O4ygHD" +
            "pTUEg+j10IlqCCBM1h7inYU/BON7S3GSS9OahIt3QnbUqzyRuQ==|76b7a1c09287d8f0a3242c7c"

    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    private fun withCrc(data: String): String = data + "6304" + EmvQr.calculateCrc16(data)

    private fun parsed(qr: String): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(BobQrParser.parse(qr)).parsed

    private fun error(qr: String): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(BobQrParser.parse(qr)).error

    @Test
    fun `encrypted envelopes pass through verbatim with no amount`() {
        assertEquals(bancoSol, parsed(bancoSol).paymentAddress)
        assertNull(parsed(bancoSol).fiatAmount)
        assertEquals(bancoFie, parsed(bancoFie).paymentAddress, "24-hex Banco Fie digest")
    }

    @Test
    fun `an EMVCo static QR returns the raw payload verbatim`() {
        val qr = withCrc("000201" + tlv("53", "068") + tlv("58", "BO") + tlv("59", "TIENDA"))
        assertEquals(qr, parsed(qr).paymentAddress)
        assertNull(parsed(qr).fiatAmount)
    }

    @Test
    fun `tag 54 becomes the fiat amount`() {
        val qr = withCrc("000201" + tlv("53", "068") + tlv("54", "1392") + tlv("58", "BO") + tlv("59", "TIENDA"))
        assertEquals(0, parsed(qr).fiatAmount?.compareTo(BigDecimal("1392")))
    }

    @Test
    fun `a non-Bolivian currency tag is rejected`() {
        val qr = withCrc("000201" + tlv("53", "840") + tlv("58", "BO") + tlv("59", "TIENDA"))
        assertIs<PaymentQrError.InvalidFormat>(error(qr))
    }

    @Test
    fun `an EMVCo QR whose CRC does not verify is rejected`() {
        assertIs<PaymentQrError.InvalidChecksum>(
            error("000201" + tlv("53", "068") + tlv("58", "BO") + tlv("59", "TIENDA") + "6304ABCD"),
        )
    }

    @Test
    fun `a packed payment id is never treated as a QR`() {
        assertIs<PaymentQrError.InvalidFormat>(error("$bancoSol||70123456"))
    }

    @Test
    fun `an envelope with a wrong-length digest is rejected`() {
        assertIs<PaymentQrError.InvalidFormat>(error(bancoSol.substringBeforeLast('|') + "|07F204D5"))
    }

    @Test
    fun `empty and blank payloads are rejected`() {
        assertIs<PaymentQrError.EmptyQr>(error(""))
        assertIs<PaymentQrError.EmptyQr>(error("   "))
    }
}
