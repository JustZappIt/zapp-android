// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import xyz.justzappit.railgun.RailgunNetwork
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateUsdAmountsTest {
    private val rupees = LocalCurrency(FiatCurrency("INR"), "₹", BigDecimal("83.456"))
    private val yen = LocalCurrency(FiatCurrency("JPY"), "¥", BigDecimal("150.5"))

    @Test
    fun `an amount finer than a token's base unit has no base units, rather than fewer`() {
        assertEquals(BigInteger.valueOf(1_500_000), BigDecimal("1.5").toBaseUnitsExact(USD_DECIMALS))
        assertEquals(BigInteger.valueOf(1_234_567), BigDecimal("1.2345670").toBaseUnitsExact(USD_DECIMALS))
        assertNull(BigDecimal("1.0000001").toBaseUnitsExact(USD_DECIMALS))
    }

    @Test
    fun `an exact token amount keeps every digit the token has, and cents at least`() {
        val token = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar }
        val fee = exactTokenAmount(BigInteger.valueOf(2_500), token) as StringResource.ByCurrencyNumber
        val whole = exactTokenAmount(BigInteger.valueOf(1_000_000), token) as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("0.002500"), fee.amount)
        assertEquals("$", fee.ticker)
        assertEquals(2, whole.minDecimals)
        assertEquals(token.decimals, whole.maxDecimals)
    }

    @Test
    fun `dollars show in the picked currency at its rate, to its cent`() {
        val local = rupees.format(BigDecimal("2.5")) as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("208.64"), local.amount)
        assertEquals("₹", local.ticker)
        assertEquals(TickerLocation.BEFORE, local.tickerLocation)
        assertEquals(2, local.maxDecimals)
    }

    @Test
    fun `a currency without cents shows none`() {
        val local = yen.format(BigDecimal.ONE) as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("150"), local.amount)
        assertEquals(0, local.minDecimals)
        assertEquals(0, local.maxDecimals)
    }

    @Test
    fun `without a rate amounts stay dollars`() {
        val local = LocalCurrency.DOLLAR.format(BigDecimal("2.5")) as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("2.50"), local.amount)
        assertEquals("$", local.ticker)
    }

    @Test
    fun `the same amount rounds the same way wherever it shows`() {
        val dollars = BigDecimal("0.1234565")
        val shown = rupees.format(dollars) as StringResource.ByCurrencyNumber

        assertEquals<Number?>(shown.amount, rupees.field(dollars).amount)
    }

    @Test
    fun `limits round inward, so both ends can be typed`() {
        val least = rupees.formatAtLeast(BigDecimal("0.11")) as StringResource.ByCurrencyNumber
        val most = rupees.formatAtMost(BigDecimal("20")) as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("9.19"), least.amount)
        assertTrue(rupees.toDollars(BigDecimal("9.19")) >= BigDecimal("0.11"))
        assertEquals(BigDecimal("1669.12"), most.amount)
        assertTrue(rupees.toDollars(BigDecimal("1669.12")) <= BigDecimal("20"))
    }

    @Test
    fun `the maximum never rounds up past what's there`() {
        val available = BigDecimal("1.123456")
        val input = checkNotNull(rupees.maximumField(available).amount)

        assertTrue(rupees.toDollars(input) <= available)
    }

    @Test
    fun `a typed amount holds only the digits its currency has`() {
        assertTrue(yen.holds(BigDecimal("100")))
        assertFalse(yen.holds(BigDecimal("100.5")))
        assertTrue(rupees.holds(BigDecimal("100.50")))
        assertFalse(rupees.holds(BigDecimal("100.505")))
    }

    private companion object {
        const val USD_DECIMALS = 6
    }
}
