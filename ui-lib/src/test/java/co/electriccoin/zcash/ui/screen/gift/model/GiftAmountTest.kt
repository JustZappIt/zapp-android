// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import cash.z.ecc.android.sdk.model.Zatoshi
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GiftAmountTest {
    @Test
    fun `accepts the smallest exactly representable amount`() {
        val amount = assertNotNull(GiftAmount.fromZec(BigDecimal("0.00000001")))

        assertEquals(Zatoshi(1L), amount.zatoshi)
    }

    @Test
    fun `accepts the inclusive Zcash monetary maximum`() {
        val amount = assertNotNull(GiftAmount.fromZec(BigDecimal("21000000")))

        assertEquals(Zatoshi(Zatoshi.MAX_INCLUSIVE), amount.zatoshi)
    }

    @Test
    fun `accepts insignificant decimal zeroes without rounding`() {
        val amount = assertNotNull(GiftAmount.fromZec(BigDecimal("1.230000000")))

        assertEquals(Zatoshi(123_000_000L), amount.zatoshi)
    }

    @Test
    fun `rejects absent zero and negative amounts`() {
        assertNull(GiftAmount.fromZec(null))
        assertNull(GiftAmount.fromZec(BigDecimal.ZERO))
        assertNull(GiftAmount.fromZec(BigDecimal("-1")))
    }

    @Test
    fun `rejects a fractional zatoshi instead of truncating it`() {
        assertNull(GiftAmount.fromZec(BigDecimal("0.000000001")))
        assertNull(GiftAmount.fromZec(BigDecimal("1.000000001")))
    }

    @Test
    fun `rejects values above the monetary range without throwing`() {
        assertNull(GiftAmount.fromZec(BigDecimal("21000000.00000001")))
        assertNull(GiftAmount.fromZec(BigDecimal("1E+100")))
    }
}
