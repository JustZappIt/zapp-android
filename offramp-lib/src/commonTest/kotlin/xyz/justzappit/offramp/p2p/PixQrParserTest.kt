// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Cross-language parity against `@p2pdotme/sdk` v1.1.7 (`test/qr-parsers/brl.test.ts`). Static
 * fixtures are lifted verbatim; the dynamic path uses an in-test [DynamicPixResolver] standing in
 * for the proxy fetch the SDK mocks. CRC verification is exercised implicitly — every accepted
 * fixture must pass [EmvQr.verifyCrc16] first.
 */
class PixQrParserTest {
    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    private fun withCrc(inner: String): String = inner + "6304" + EmvQr.calculateCrc16(inner)

    private fun pix(vararg fields: String): String =
        tlv("00", "01") + tlv("53", "986") + tlv("58", "BR") + fields.joinToString("")

    private suspend fun parsed(qr: String, resolver: DynamicPixResolver? = null): ParsedPaymentQr =
        assertIs<PaymentQrParseResult.Success>(PixQrParser.parse(qr, resolver)).parsed

    private suspend fun error(qr: String, resolver: DynamicPixResolver? = null): PaymentQrError =
        assertIs<PaymentQrParseResult.Failure>(PixQrParser.parse(qr, resolver)).error

    // -- static ---------------------------------------------------------------------------------

    @Test
    fun `parses merchant with static amount`() =
        runTest {
            val data = parsed(withCrc(pix(tlv("59", "LOJA"), tlv("54", "25.00"))))
            assertEquals("LOJA", data.paymentAddress)
            assertEquals(BigDecimal("25.00"), data.fiatAmount)
        }

    @Test
    fun `uses MERCHANT_NOT_FOUND when tag 59 absent`() =
        runTest {
            val data = parsed(withCrc(pix(tlv("54", "10.00"))))
            assertEquals("MERCHANT_NOT_FOUND", data.paymentAddress)
        }

    @Test
    fun `ok without amount when tag 54 absent`() =
        runTest {
            assertNull(parsed(withCrc(pix(tlv("59", "LOJA")))).fiatAmount)
        }

    @Test
    fun `empty or whitespace is EmptyQr`() =
        runTest {
            assertIs<PaymentQrError.EmptyQr>(error(""))
            assertIs<PaymentQrError.EmptyQr>(error("   "))
        }

    @Test
    fun `crc mismatch is InvalidChecksum`() =
        runTest {
            val inner = pix(tlv("59", "LOJA"))
            assertIs<PaymentQrError.InvalidChecksum>(error(inner + "6304FFFF"))
        }

    @Test
    fun `missing payload-format tag 00 is InvalidFormat`() =
        runTest {
            assertIs<PaymentQrError.InvalidFormat>(error(withCrc(tlv("59", "LOJA") + tlv("54", "10.00"))))
        }

    @Test
    fun `present but invalid static amount is InvalidAmount`() =
        runTest {
            val qr = withCrc(pix(tlv("59", "LOJA"), tlv("54", "123abc")))

            assertIs<PaymentQrError.InvalidAmount>(error(qr))
        }

    // -- dynamic --------------------------------------------------------------------------------

    @Test
    fun `fetches dynamic amount via resolver and normalizes the location url`() =
        runTest {
            var capturedUrl: String? = null
            var capturedOrderId: String? = null
            val resolver =
                DynamicPixResolver { url, orderId ->
                    capturedUrl = url
                    capturedOrderId = orderId
                    "55.00"
                }
            val inner = pix(tlv("26", tlv("25", "pix.example.com/loc/abc")), tlv("59", "LOJA"))
            val data =
                assertIs<PaymentQrParseResult.Success>(
                    PixQrParser.parse(withCrc(inner), resolver, orderId = "order-123"),
                ).parsed
            assertEquals(BigDecimal("55.00"), data.fiatAmount)
            assertEquals("https://pix.example.com/loc/abc", capturedUrl)
            assertEquals("order-123", capturedOrderId)
        }

    @Test
    fun `dynamic without resolver is DynamicFetchFailed`() =
        runTest {
            val inner = pix(tlv("26", tlv("25", "pix.example.com/loc/abc")), tlv("59", "LOJA"))
            assertIs<PaymentQrError.DynamicFetchFailed>(error(withCrc(inner), resolver = null))
        }

    @Test
    fun `dynamic resolver failure is DynamicFetchFailed`() =
        runTest {
            val resolver = DynamicPixResolver { _, _ -> throw IllegalStateException("network down") }
            val inner = pix(tlv("26", tlv("25", "pix.example.com/x")), tlv("59", "LOJA"))
            assertIs<PaymentQrError.DynamicFetchFailed>(error(withCrc(inner), resolver))
        }

    @Test
    fun `present but invalid dynamic amount is InvalidAmount`() =
        runTest {
            val resolver = DynamicPixResolver { _, _ -> "123abc" }
            val inner = pix(tlv("26", tlv("25", "pix.example.com/x")), tlv("59", "LOJA"))

            assertIs<PaymentQrError.InvalidAmount>(error(withCrc(inner), resolver))
        }

    @Test
    fun `rejects a non-BRL EMV payload`() =
        runTest {
            val inner = tlv("00", "01") + tlv("53", "360") + tlv("58", "ID") + tlv("59", "LOJA")
            assertIs<PaymentQrError.InvalidFormat>(error(withCrc(inner)))
        }

    // -- real-world EMVCo MPM fixtures from the SDK suite --------------------------------------

    @Test
    fun `static PIX email key with amount`() =
        runTest {
            val qr =
                "00020126340014BR.GOV.BCB.PIX0112foo@bar.test5204000053039865406150.005802BR" +
                    "5914NOME RECEBEDOR6008CIDADE A62100506PED00163045F53"
            val data = parsed(qr)
            assertEquals("NOME RECEBEDOR", data.paymentAddress)
            assertEquals(BigDecimal("150.00"), data.fiatAmount)
        }

    @Test
    fun `static PIX CPF key no amount`() =
        runTest {
            val qr =
                "00020126330014BR.GOV.BCB.PIX0111000000000005204000053039865802BR" +
                    "5910NOME TESTE6008CIDADE B62070503***6304D727"
            val data = parsed(qr)
            assertEquals("NOME TESTE", data.paymentAddress)
            assertNull(data.fiatAmount)
        }

    @Test
    fun `static PIX EVP key with amount`() =
        runTest {
            val qr =
                "00020126580014BR.GOV.BCB.PIX013600000000-0000-0000-0000-000000000000520400005303986540542.50" +
                    "5802BR5917COMERCIO FICTICIO6008CIDADE C62070503***63048DDF"
            val data = parsed(qr)
            assertEquals("COMERCIO FICTICIO", data.paymentAddress)
            assertEquals(BigDecimal("42.50"), data.fiatAmount)
        }

    @Test
    fun `dynamic PIX real fixture resolves via proxy`() =
        runTest {
            var capturedUrl: String? = null
            val resolver =
                DynamicPixResolver { url, _ ->
                    capturedUrl = url
                    "89.90"
                }
            val qr =
                "00020101021226590014BR.GOV.BCB.PIX2537example.test/v2/cobv/aaaabbbbccccdddd5204000053039865802BR" +
                    "5910LOJA TESTE6008CIDADE D62160512ORDEMTEST0016304B02B"
            val data = parsed(qr, resolver)
            assertEquals("LOJA TESTE", data.paymentAddress)
            assertEquals(BigDecimal("89.90"), data.fiatAmount)
            assertEquals("https://example.test/v2/cobv/aaaabbbbccccdddd", capturedUrl)
        }
}
