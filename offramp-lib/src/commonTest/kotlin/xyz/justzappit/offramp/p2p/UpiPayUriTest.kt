// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpiPayUriTest {
    @Test
    fun `build emits canonical upi pay URI with all fields percent-encoded`() {
        val uri =
            UpiPayUri.build(
                vpa = "merchant@okhdfcbank",
                payeeName = "Yuvasri 2002",
                inrAmount = BigDecimal("37.28"),
            )
        assertEquals("upi://pay?pa=merchant@okhdfcbank&pn=Yuvasri%202002&am=37.28&cu=INR", uri)
    }

    @Test
    fun `build omits pn when blank or null`() {
        val withoutName = UpiPayUri.build(vpa = "u@b", inrAmount = BigDecimal("10.00"))
        val withBlank = UpiPayUri.build(vpa = "u@b", payeeName = "   ", inrAmount = BigDecimal("10.00"))
        assertEquals("upi://pay?pa=u@b&am=10.00&cu=INR", withoutName)
        assertEquals(withoutName, withBlank)
    }

    @Test
    fun `build truncates fractional INR below the paise`() {
        val uri = UpiPayUri.build(vpa = "u@b", inrAmount = BigDecimal("37.289999"))
        assertTrue(uri.contains("am=37.28"), "expected floor at 2dp, got: $uri")
    }

    @Test
    fun `build rejects non-positive amount`() {
        assertFailsWith<IllegalArgumentException> {
            UpiPayUri.build(vpa = "u@b", inrAmount = BigDecimal("0"))
        }
    }

    @Test
    fun `parsedUsdcMicros mirrors SDK parseAmount flooring at 6 decimals`() {
        val micros =
            UpiPayUri.parsedUsdcMicros(
                inrAmount = BigDecimal("445.00"),
                sellPriceInrPerUsdc = BigDecimal("89.00"),
            )
        assertEquals(5_000_000L, micros)
    }

    @Test
    fun `parsedUsdcMicros floors so user never gets more than they typed`() {
        val micros =
            UpiPayUri.parsedUsdcMicros(
                inrAmount = BigDecimal("36"),
                sellPriceInrPerUsdc = BigDecimal("93.20"),
            )
        assertEquals(386_266L, micros)
    }
}
