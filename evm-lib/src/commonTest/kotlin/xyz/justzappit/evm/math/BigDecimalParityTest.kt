// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

import kotlin.test.Test
import kotlin.test.assertEquals

class BigDecimalParityTest {
    @Test
    fun `half-up and down rounding match fiat rules`() {
        assertEquals("12.35", scaled("12.345", DecimalRounding.HALF_UP))
        assertEquals("-12.35", scaled("-12.345", DecimalRounding.HALF_UP))
        assertEquals("12.34", scaled("12.349", DecimalRounding.DOWN))
        assertEquals("-12.34", scaled("-12.349", DecimalRounding.DOWN))
    }

    @Test
    fun `division scale and decimal-point movement match Java`() {
        val divided = decimalDivide(BigDecimal("10"), BigDecimal("3"), 6, DecimalRounding.DOWN)
        assertEquals("3.333333", decimalToPlainString(divided))
        assertEquals("1.000000", decimalToPlainString(decimalMovePointLeft(BigDecimal("1000000"), 6)))
        assertEquals(
            bigIntegerValueOf(1_234_568),
            decimalToBigInteger(
                decimalSetScale(
                    decimalMovePointRight(BigDecimal("1.2345678"), 6),
                    0,
                    DecimalRounding.HALF_UP,
                ),
            ),
        )
    }

    private fun scaled(value: String, rounding: DecimalRounding): String =
        decimalToPlainString(decimalSetScale(BigDecimal(value), 2, rounding))
}
