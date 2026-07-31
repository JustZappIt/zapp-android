// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.test.Test
import kotlin.test.assertEquals

class P2pOrderHistoryUtilsTest {
    @Test
    fun `extractUpiVpa pulls pa from a full URI`() {
        assertEquals(
            "merchant@okhdfcbank",
            extractUpiVpa("upi://pay?pa=merchant@okhdfcbank&pn=Test&am=37.28&cu=INR"),
        )
    }

    @Test
    fun `extractUpiVpa returns input verbatim when not a URI`() {
        assertEquals("bare@vpa", extractUpiVpa("bare@vpa"))
    }

    @Test
    fun `extractUpiVpa is case-insensitive on the scheme`() {
        assertEquals("a@b", extractUpiVpa("UPI://PAY?pa=a@b&am=1"))
    }
}
