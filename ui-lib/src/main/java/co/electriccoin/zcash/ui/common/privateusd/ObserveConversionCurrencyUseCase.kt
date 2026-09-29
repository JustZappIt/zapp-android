// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.math.BigDecimal
import java.math.RoundingMode

class ObserveConversionCurrencyUseCase(
    private val preferredFiat: PreferredFiatProvider,
    private val observeDollarRate: ObserveDollarRateUseCase,
) {
    operator fun invoke() =
        combine(preferredFiat.observe(), observeDollarRate()) { preferred, rate ->
            val currency = preferred ?: FiatCurrency.USD
            ConversionCurrency(
                currency.symbol,
                if (currency ==
                    FiatCurrency.USD
                ) {
                    BigDecimal.ONE
                } else {
                    rate?.takeIf { it.currency == currency }?.perDollar
                },
            )
        }.distinctUntilChanged()
}

data class ConversionCurrency(
    val symbol: String,
    val perDollar: BigDecimal?
) {
    init {
        require(perDollar == null || perDollar.signum() > 0)
    }

    fun local(dollars: BigDecimal): BigDecimal? = perDollar?.let { dollars * it }

    fun units(amount: BigDecimal): Int? =
        try {
            perDollar?.let {
                amount
                    .divide(
                        it,
                        USDC_DECIMALS,
                        RoundingMode.DOWN
                    ).movePointRight(USDC_DECIMALS)
                    .intValueExact()
            }
        } catch (_: ArithmeticException) {
            null
        }

    fun maximum(dollars: BigDecimal): BigDecimal? = local(dollars)?.setScale(FIAT_DECIMALS, RoundingMode.DOWN)

    companion object {
        const val USDC_DECIMALS = 6
        const val FIAT_DECIMALS = 2
    }
}

fun ConversionCurrency?.format(dollars: BigDecimal): StringResource =
    this?.local(dollars)?.let {
        stringResByCurrencyNumber(
            amount = it,
            ticker = symbol,
            tickerLocation = TickerLocation.BEFORE,
            minDecimals = ConversionCurrency.FIAT_DECIMALS,
            maxDecimals = ConversionCurrency.FIAT_DECIMALS,
        )
    } ?: stringRes(R.string.convert_currency_loading)
