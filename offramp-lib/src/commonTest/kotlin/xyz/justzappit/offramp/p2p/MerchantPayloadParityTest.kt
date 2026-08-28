// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Proves Android's supported merchant payload shapes reach the matching production parser. */
class MerchantPayloadParityTest {
    @Test
    fun `Android merchant payloads pass through the shared production dispatcher`() =
        runTest {
            val cases =
                listOf(
                    Case(CurrencyCode.Inr, "upi://pay?pa=merchant@okaxis&pn=Merchant&am=100&cu=INR", "merchant@okaxis"),
                    Case(
                        CurrencyCode.Brl,
                        "00020126340014BR.GOV.BCB.PIX0112foo@bar.test5204000053039865406150.005802BR" +
                            "5914NOME RECEBEDOR6008CIDADE A62100506PED00163045F53",
                        "NOME RECEBEDOR",
                    ),
                    Case(
                        CurrencyCode.Idr,
                        "00020101021126580013ID.CO.BRI.WWW01189360000200413325550208413325550303UMI51440014" +
                            "ID.CO.QRIS.WWW0215ID10243358898860303UMI5204591253033605802ID5914APOTIK SYAHADA" +
                            "6006PADANG61052517162070703A01630490C5",
                        "APOTIK SYAHADA",
                    ),
                    Case(
                        CurrencyCode.Ars,
                        "00020101021226410016com.mercadolibre01090000000000204000052045411530303254072500.00" +
                            "5802AR5912COMERCIO UNO6008CIUDAD A63046A3A",
                        "COMERCIO UNO",
                    ),
                    Case(CurrencyCode.Ven, "SGVsbG9Xb3JsZA==?bank=BANCO_A", "SGVsbG9Xb3JsZA==?bank=BANCO_A"),
                    Case(CurrencyCode.Ngn, "SPD*1.0*ACC:1234567890*AM:40,000.00*MSG:Test*", "1234567890"),
                    Case(
                        CurrencyCode.Cop,
                        "NumFac: TEST00000001\nNitFac: 900000000\nValFac: 100.00\nCUFE: ${"0".repeat(96)}",
                        "0".repeat(96),
                    ),
                ) + newCorridorCases()

            cases.forEach { case ->
                val parsed =
                    assertIs<PaymentQrParseResult.Success>(
                        PaymentQrParser.parse(case.currency, case.payload),
                        case.currency.code,
                    )
                assertEquals(case.paymentAddress, parsed.parsed.paymentAddress, case.currency.code)
            }
        }

    /** The corridors added alongside SDK 1.2.21. PEN/PHP/BOB hand back the whole payload. */
    private fun newCorridorCases(): List<Case> {
        fun tlv(tag: String, value: String) = tag + value.length.toString().padStart(2, '0') + value

        fun withCrc(data: String) = data + "6304" + EmvQr.calculateCrc16(data)

        val pen = withCrc("000201" + tlv("53", "604") + tlv("58", "PE") + tlv("59", "YAPERO"))
        val php = withCrc("000201" + tlv("53", "608") + tlv("58", "PH") + tlv("59", "TEST SHOP"))
        val bob = withCrc("000201" + tlv("53", "068") + tlv("58", "BO") + tlv("59", "TIENDA"))
        return listOf(
            Case(CurrencyCode.Pen, pen, pen),
            Case(CurrencyCode.Php, php, php),
            Case(CurrencyCode.Bob, bob, bob),
            Case(
                CurrencyCode.Cup,
                "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,58555555,",
                "58555555|9204959800000000",
            ),
            Case(
                CurrencyCode.Ecu,
                "https://pagar.deuna.app/demo/merchant?id=demomerchant123",
                "demomerchant123",
            ),
        )
    }

    private data class Case(
        val currency: CurrencyCode,
        val payload: String,
        val paymentAddress: String,
    )
}
