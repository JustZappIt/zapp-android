// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.stringRes
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionCurrencyTest {
    private val rupees = ConversionCurrency("₹", BigDecimal("83.5"))

    @Test
    fun typedRupeesBecomeExactTokenUnits() {
        assertEquals(1_000_000, rupees.units(BigDecimal("83.50")))
        assertEquals(BigDecimal("167.0"), rupees.local(BigDecimal("2")))
        assertNull(rupees.units(BigDecimal("999999999999999999999999999999")))
    }

    @Test
    fun maximumNeverRoundsAboveTheAvailableAmount() {
        val available = BigDecimal("1.123456")
        val input = checkNotNull(rupees.maximum(available))
        val units = checkNotNull(rupees.units(input))
        assertTrue(BigDecimal.valueOf(units.toLong(), ConversionCurrency.USDC_DECIMALS) <= available)
    }

    @Test
    fun missingLocalRateDoesNotTreatRupeesAsDollars() {
        val unavailable = ConversionCurrency("₹", null)
        assertNull(unavailable.units(BigDecimal("100")))
        assertNull(unavailable.local(BigDecimal.ONE))
        assertEquals(stringRes(R.string.convert_currency_loading), unavailable.format(BigDecimal.ONE))
    }
}
