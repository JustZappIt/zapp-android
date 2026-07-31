// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Rail detection for the generic home scanner. A false positive routes a Zcash (or garbage) scan
 * into the offramp; a false negative rejects a real merchant QR. The IDR fixture is a real decoded
 * QRIS photo — it must both detect as [CurrencyCode.Idr] and survive [QrisQrParser.parse], because
 * the home scanner detects then parses before it routes.
 */
class PaymentQrDetectorTest {
    @Test
    fun `real QRIS photo detects as IDR and parses`() =
        runTest {
            val qr =
                "00020101021126580013ID.CO.BRI.WWW01189360000200413325550208413325550303UMI51440014" +
                    "ID.CO.QRIS.WWW0215ID10243358898860303UMI5204591253033605802ID5914APOTIK SYAHADA" +
                    "6006PADANG61052517162070703A01630490C5"
            assertEquals(CurrencyCode.Idr, PaymentQrDetector.detect(qr))

            val parsed = assertIs<PaymentQrParseResult.Success>(PaymentQrParser.parse(CurrencyCode.Idr, qr)).parsed
            assertEquals("APOTIK SYAHADA", parsed.paymentAddress)
            assertNull(parsed.fiatAmount)
        }

    @Test
    fun `upi uri detects as INR`() {
        assertEquals(CurrencyCode.Inr, PaymentQrDetector.detect("upi://pay?pa=merchant@upi&am=250"))
    }

    @Test
    fun `upi scheme is case insensitive`() {
        assertEquals(CurrencyCode.Inr, PaymentQrDetector.detect("UPI://pay?pa=merchant@upi"))
    }

    @Test
    fun `static PIX EMV detects as BRL`() {
        val qr =
            "00020126330014BR.GOV.BCB.PIX0111000000000005204000053039865802BR" +
                "5910NOME TESTE6008CIDADE B62070503***6304D727"
        assertEquals(CurrencyCode.Brl, PaymentQrDetector.detect(qr))
    }

    @Test
    fun `ARS EMV detects as ARS`() {
        assertEquals(CurrencyCode.Ars, PaymentQrDetector.detect("000201" + "5303032" + "5902XY"))
    }

    @Test
    fun `NGN EMV detects as NGN`() {
        assertEquals(CurrencyCode.Ngn, PaymentQrDetector.detect("000201" + "5303566" + "5902XY"))
    }

    @Test
    fun `COP EMV detects as COP`() {
        assertEquals(CurrencyCode.Cop, PaymentQrDetector.detect("000201" + "5303170" + "5902XY"))
    }

    @Test
    fun `VEN base64 payload is not detected without an EMV currency tag`() {
        assertNull(PaymentQrDetector.detect("SGVsbG9Xb3JsZA==?bank=BANCO_A"))
    }

    @Test
    fun `zcash unified address is not a payment QR`() {
        val unified =
            "u1l9f0l4348negsncgr9pxd9d3qaxagmqv3lnexcplmrn2z5m5e3 spgq9km0eq5 suy6dktlfqp67qdff7cn6d0z"
        assertNull(PaymentQrDetector.detect(unified.replace(" ", "")))
    }

    @Test
    fun `transparent address is not a payment QR`() {
        assertNull(PaymentQrDetector.detect("t1RwzsPgUBfz7dcSCFhoZbvzTsy9L4KKgbP"))
    }

    @Test
    fun `random text is not a payment QR`() {
        assertNull(PaymentQrDetector.detect("just some scanned text"))
    }

    @Test
    fun `empty and blank are not payment QRs`() {
        assertNull(PaymentQrDetector.detect(""))
        assertNull(PaymentQrDetector.detect("   "))
    }

    @Test
    fun `digit-leading non-EMV string is rejected by the tag-00 guard`() {
        // The TLV reader would parse tags out of this, but tag 00 != "01" so it isn't an EMVCo MPM.
        assertNull(PaymentQrDetector.detect("1234567890123456789012345"))
    }

    @Test
    fun `EMV with unsupported currency is not detected`() {
        // Payload-format tag present but INR (356) EMV isn't supported (UpiQrParser is upi:// only).
        val inrEmv = "000201" + "5303356" + "5902XY"
        assertNull(PaymentQrDetector.detect(inrEmv))
    }

    @Test
    fun `shared parser rejects oversized payload before rail parsing`() =
        runTest {
            val oversized = "upi://pay?pa=merchant@upi&pn=" + "a".repeat(16 * 1024)
            assertIs<PaymentQrParseResult.Failure>(PaymentQrParser.parse(CurrencyCode.Inr, oversized))
        }
}
