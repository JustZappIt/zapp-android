// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaymentQrDetectorParityTest {
    @Test
    fun detectsEverySniffableCorridor() {
        assertEquals(CurrencyCode.Inr, PaymentQrDetector.detect("upi://pay?pa=merchant@upi"))
        assertEquals(CurrencyCode.Idr, PaymentQrDetector.detect("0002015303360"))
        assertEquals(CurrencyCode.Brl, PaymentQrDetector.detect("0002015303986"))
        assertEquals(CurrencyCode.Ars, PaymentQrDetector.detect("0002015303032"))
        assertEquals(CurrencyCode.Ngn, PaymentQrDetector.detect("0002015303566"))
        assertEquals(CurrencyCode.Cop, PaymentQrDetector.detect("0002015303170"))
        assertEquals(CurrencyCode.Bob, PaymentQrDetector.detect("0002015303068"))
        assertEquals(CurrencyCode.Pen, PaymentQrDetector.detect("0002015303604"))
        assertEquals(CurrencyCode.Php, PaymentQrDetector.detect("0002015303608"))
    }

    /**
     * ECU is dollarised and CUP is not EMVCo, so neither can be sniffed — claiming USD would
     * misroute every dollar-denominated QR to Ecuador. Both are reached by order currency alone.
     */
    @Test
    fun doesNotSniffTheDollarisedOrNonEmvCorridors() {
        assertNull(PaymentQrDetector.detect("https://pagar.deuna.app/demo/merchant?id=demomerchant123"))
        assertNull(PaymentQrDetector.detect("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204959800000000,58555555,"))
        assertNull(PaymentQrDetector.detect("0002015303840"))
    }

    @Test
    fun rejectsUnidentifiedPayloads() {
        assertNull(PaymentQrDetector.detect("SGVsbG9Xb3JsZA==?bank=BANCO_A"))
        assertNull(PaymentQrDetector.detect("t1RwzsPgUBfz7dcSCFhoZbvzTsy9L4KKgbP"))
        assertNull(PaymentQrDetector.detect(""))
    }
}
