// SPDX-License-Identifier: MIT OR Apache-2.0
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
    }

    @Test
    fun rejectsUnidentifiedPayloads() {
        assertNull(PaymentQrDetector.detect("SGVsbG9Xb3JsZA==?bank=BANCO_A"))
        assertNull(PaymentQrDetector.detect("t1RwzsPgUBfz7dcSCFhoZbvzTsy9L4KKgbP"))
        assertNull(PaymentQrDetector.detect(""))
    }
}
