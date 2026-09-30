// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import xyz.justzappit.offramp.peer.Bps
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.Currency

private const val CENTS = 2
private const val PERCENT_SCALE = 2

/** [symbol] is resolved once, since [FiatCurrency.symbol] needs the platform. */
data class LocalCurrency(
    val currency: FiatCurrency,
    val symbol: String,
    val perDollar: BigDecimal,
) {
    init {
        require(perDollar.signum() > 0)
    }

    val isDollar: Boolean get() = currency == FiatCurrency.USD

    /** Digits after the point: none for yen, two for most. */
    val decimals: Int = currency.fractionDigits()

    fun format(dollars: BigDecimal): StringResource = money(of(dollars, RoundingMode.HALF_EVEN))

    /** A lower limit in this currency, rounded up so the amount shown is one that can be typed. */
    fun formatAtLeast(dollars: BigDecimal): StringResource = money(of(dollars, RoundingMode.UP))

    /** An upper limit in this currency, rounded down for the same reason. */
    fun formatAtMost(dollars: BigDecimal): StringResource = money(of(dollars, RoundingMode.DOWN))

    /** [dollars] as an amount field in this currency shows them. */
    fun field(dollars: BigDecimal): NumberTextFieldInnerState = input(of(dollars, RoundingMode.HALF_EVEN))

    /** The most of this currency [dollars] cover, as an amount field shows it. */
    fun maximumField(dollars: BigDecimal): NumberTextFieldInnerState = input(of(dollars, RoundingMode.DOWN))

    fun toDollars(amount: BigDecimal): BigDecimal = amount.divide(perDollar, MathContext.DECIMAL128)

    /** Whether [amount] fits this currency, as "0.5" doesn't yen. */
    fun holds(amount: BigDecimal): Boolean = amount.stripTrailingZeros().scale() <= decimals

    private fun of(
        dollars: BigDecimal,
        rounding: RoundingMode
    ): BigDecimal = (dollars * perDollar).setScale(decimals, rounding)

    private fun money(amount: BigDecimal): StringResource =
        stringResByCurrencyNumber(
            amount = amount,
            ticker = symbol,
            tickerLocation = TickerLocation.BEFORE,
            minDecimals = decimals,
            maxDecimals = decimals,
        )

    private fun input(amount: BigDecimal) =
        NumberTextFieldInnerState(
            innerTextFieldState = InnerTextFieldState(stringResByNumber(amount, decimals, decimals)),
            amount = amount,
            lastValidAmount = amount,
        )

    companion object {
        val DOLLAR = LocalCurrency(FiatCurrency.USD, "$", BigDecimal.ONE)
    }
}

fun BigInteger.toDecimal(decimals: Int): BigDecimal = BigDecimal(this, decimals)

/** [fee] as a percentage, without its sign: 25 basis points are 0.25. */
fun basisPoints(fee: Bps): StringResource {
    val percent = BigDecimal.valueOf(fee.value.toLong(), PERCENT_SCALE)
    return stringResByNumber(percent, minDecimals = 0, maxDecimals = PERCENT_SCALE)
}

fun BigDecimal.toBaseUnits(decimals: Int): BigInteger =
    movePointRight(decimals).setScale(0, RoundingMode.DOWN).toBigInteger()

/** In base units of a token with [decimals]; null for an amount finer than one of them. */
fun BigDecimal.toBaseUnitsExact(decimals: Int): BigInteger? =
    try {
        movePointRight(decimals).toBigIntegerExact()
    } catch (_: ArithmeticException) {
        null
    }

fun dollars(amount: BigDecimal): StringResource = LocalCurrency.DOLLAR.format(amount)

/** [amount] of a dollar token, to the cent. */
fun tokenAmount(
    amount: BigInteger,
    token: PrivateUsdToken,
): StringResource = dollars(amount.toDecimal(token.decimals))

/** [amount] of a dollar token to its last base unit, so a fee and what's left after it add up to what was sent. */
fun exactTokenAmount(
    amount: BigInteger,
    token: PrivateUsdToken,
): StringResource =
    stringResByCurrencyNumber(
        amount = amount.toDecimal(token.decimals),
        ticker = LocalCurrency.DOLLAR.symbol,
        tickerLocation = TickerLocation.BEFORE,
        minDecimals = LocalCurrency.DOLLAR.decimals,
        maxDecimals = token.decimals,
    )

// Unknown codes show cents.
private fun FiatCurrency.fractionDigits(): Int =
    try {
        Currency.getInstance(code).defaultFractionDigits.takeIf { it >= 0 } ?: CENTS
    } catch (_: IllegalArgumentException) {
        CENTS
    }
