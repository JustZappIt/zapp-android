package co.electriccoin.zcash.ui.screen.invest.common

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The currency Invest shows money in: the user's own, like the PAY balance (decided 2026-09-27). 1Click prices
 * stocks in USD, so every figure is converted with [perUsd]; with no rate to hand it stays in USD.
 */
data class InvestCurrency(
    val code: String,
    val symbol: String,
    /** Units of this currency per US dollar. */
    val perUsd: BigDecimal,
) {
    val isUsd: Boolean get() = code == USD_CODE

    fun fromUsd(usd: BigDecimal): BigDecimal = if (isUsd) usd else usd.multiply(perUsd, MathContext.DECIMAL64)

    /** A local amount in USD, to the cent and never above what was typed; USD passes through unchanged. */
    fun toUsd(local: BigDecimal): BigDecimal = if (isUsd) local else local.divide(perUsd, 2, RoundingMode.DOWN)

    /** A USD amount in this currency, e.g. "$1,204.87", "€1,112.40" or "IDR 19,654,321.00". */
    fun format(usd: BigDecimal): String = formatLocal(fromUsd(usd))

    /** A guaranteed floor ("at least") in this currency: converted first, then rounded down, never up. */
    fun formatAtLeast(usd: BigDecimal): String = formatLocal(fromUsd(usd), RoundingMode.DOWN)

    /** An amount already in this currency. */
    fun formatLocal(
        local: BigDecimal,
        rounding: RoundingMode = RoundingMode.HALF_UP,
    ): String {
        val number = DecimalFormat("#,##0.00", SYMBOLS).format(local.setScale(2, rounding))
        // A letter code ("IDR") reads better with a space; a sign ("$", "€", "R$") sits against the number.
        return if (symbol.lastOrNull()?.isLetter() == true) "$symbol $number" else "$symbol$number"
    }

    /** A preset in this currency: [usd] converted and rounded up to two significant figures ("$40", "€37"). */
    fun presetFromUsd(usd: BigDecimal): BigDecimal {
        val local = fromUsd(usd)
        return if (isUsd) local else local.round(MathContext(PRESET_DIGITS, RoundingMode.CEILING)).stripTrailingZeros()
    }

    /** A whole-number preset label, e.g. "$40" or "IDR 660,000". */
    fun formatPreset(local: BigDecimal): String = formatLocal(local).removeSuffix(".00")

    companion object {
        val USD = InvestCurrency(code = "USD", symbol = "$", perUsd = BigDecimal.ONE)
        private val SYMBOLS = DecimalFormatSymbols(Locale.US)
        private const val PRESET_DIGITS = 2
        private const val USD_CODE = "USD"
    }
}

fun interface InvestCurrencyProvider {
    fun observe(): Flow<InvestCurrency>
}

/**
 * The PAY balance's conversion: the exchange-rate opt-in's price of ZEC in the chosen currency, divided by 1Click's
 * USD price of ZEC, gives that currency per dollar. Without either, Invest stays in USD.
 */
class InvestCurrencyProviderImpl(
    private val exchangeRateRepository: ExchangeRateRepository,
    private val swapRepository: SwapRepository,
) : InvestCurrencyProvider {
    override fun observe(): Flow<InvestCurrency> =
        combine(exchangeRateRepository.state, swapRepository.assets) { exchangeRate, assets ->
            val zecUsd = assets.zecAsset?.usdPrice?.takeIf { it.signum() > 0 }
            val rate = zecFiatRate(exchangeRate, zecUsd)
            if (zecUsd == null || rate == null || rate.currency == FiatCurrency.USD) {
                InvestCurrency.USD
            } else {
                InvestCurrency(
                    code = rate.currency.code,
                    symbol = rate.symbol,
                    perUsd = rate.pricePerZec.divide(zecUsd, MathContext.DECIMAL64),
                )
            }
        }.distinctUntilChanged()
}
