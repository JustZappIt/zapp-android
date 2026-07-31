package co.electriccoin.zcash.ui.common.wallet

import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import java.math.BigDecimal
import java.math.MathContext

/** The fiat price to display for one ZEC, plus the currency symbol to render it with. */
data class ZecFiatRate(
    val pricePerZec: BigDecimal,
    val currency: FiatCurrency,
) {
    val symbol: String
        get() = currency.symbol

    fun zecToFiat(zec: BigDecimal): BigDecimal = zec.multiply(pricePerZec, MathContext.DECIMAL128)

    fun fiatToZec(fiat: BigDecimal): BigDecimal = fiat.divide(pricePerZec, MathContext.DECIMAL128)
}

/** Formats [zatoshi] as a fiat amount string in this rate's currency (symbol leads, e.g. `$1.23`). */
fun ZecFiatRate.toFiatString(zatoshi: Zatoshi): StringResource =
    stringResByDynamicCurrencyNumber(
        amount = zecToFiat(zatoshi.convertZatoshiToZec()),
        ticker = symbol,
        tickerLocation = TickerLocation.BEFORE
    )

/**
 * Resolves the fiat rate shared by the balance card and the activity rows so both surface fiat
 * from the same source. The CMC opt-in rate wins when present; if the user expects a non-USD
 * currency that has not loaded yet, no fiat is shown rather than a wrong-currency figure;
 * otherwise it falls back to [zecUsdPrice] from the always-on swap-asset catalog. Null means
 * show no fiat.
 */
fun zecFiatRate(
    exchangeRate: ExchangeRateState?,
    zecUsdPrice: BigDecimal?,
): ZecFiatRate? {
    val data = exchangeRate as? ExchangeRateState.Data
    val conversion = data?.currencyConversion
    if (conversion != null) {
        return conversion.toZecFiatRate()
    }
    return when {
        data != null && data.expectedCurrency != FiatCurrency.USD -> null
        zecUsdPrice != null && zecUsdPrice.signum() > 0 -> ZecFiatRate(zecUsdPrice, FiatCurrency.USD)
        else -> null
    }
}

fun FiatCurrencyConversion.toZecFiatRate(): ZecFiatRate? =
    priceOfZec
        .takeIf { it > 0.0 }
        ?.let { ZecFiatRate(it.toBigDecimal(), fiatCurrency) }
