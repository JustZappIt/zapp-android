// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

private const val CENTS = 2
private const val MAX_TOKEN_DECIMALS = 6

/** What a dollar is worth in the user's currency, which shows as [symbol]. */
data class DollarRate(
    val symbol: String,
    val perDollar: BigDecimal,
    val currency: FiatCurrency? = null,
)

fun BigInteger.toDecimal(decimals: Int): BigDecimal = BigDecimal(this, decimals)

fun BigDecimal.toBaseUnits(decimals: Int): BigInteger =
    movePointRight(decimals).setScale(0, RoundingMode.DOWN).toBigInteger()

/** Dollars to the cent, rounded down unless it is an estimate. */
fun dollars(
    amount: BigDecimal,
    estimate: Boolean = false
): StringResource = money(amount.setScale(CENTS, if (estimate) RoundingMode.HALF_UP else RoundingMode.DOWN), "$")

/** [amount] dollars in the user's currency, or as dollars without a rate. */
fun DollarRate?.local(amount: BigDecimal): StringResource =
    if (this == null) {
        dollars(amount)
    } else {
        money((amount * perDollar).setScale(CENTS, RoundingMode.HALF_UP), symbol)
    }

fun tokenAmount(
    amount: BigInteger,
    token: PrivateUsdToken,
    estimate: Boolean = false,
): StringResource =
    if (token.isDollar) {
        dollars(amount.toDecimal(token.decimals), estimate)
    } else {
        stringResByCurrencyNumber(
            amount = amount.toDecimal(token.decimals).setScale(MAX_TOKEN_DECIMALS, RoundingMode.DOWN),
            ticker = token.symbol,
            tickerLocation = TickerLocation.AFTER,
            minDecimals = 0,
            maxDecimals = MAX_TOKEN_DECIMALS,
        )
    }

private fun money(
    amount: BigDecimal,
    symbol: String
): StringResource =
    stringResByCurrencyNumber(
        amount = amount,
        ticker = symbol,
        tickerLocation = TickerLocation.BEFORE,
        minDecimals = CENTS,
        maxDecimals = CENTS,
    )
