// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivateUsdAmountsTest {
    @Test
    fun `dollars show in the user's currency at its rate, to the cent`() {
        val local = DollarRate("₹", BigDecimal("83.456")).local(BigDecimal("2.5")) as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("208.64"), local.amount)
        assertEquals("₹", local.ticker)
        assertEquals(TickerLocation.BEFORE, local.tickerLocation)
    }

    @Test
    fun `without a rate they stay dollars`() {
        val noRate: DollarRate? = null
        val local = noRate.local(BigDecimal("2.5")) as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("2.50"), local.amount)
        assertEquals("$", local.ticker)
    }
}
