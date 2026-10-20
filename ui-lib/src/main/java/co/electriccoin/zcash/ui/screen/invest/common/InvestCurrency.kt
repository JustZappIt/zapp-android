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

/** The currency Invest shows money in: the user's own, like the PAY balance, or USD without a rate. */
data class InvestCurrency(
    val code: String,
    val symbol: String,
    /** Units of this currency per US dollar. */
    val perUsd: BigDecimal,
) {
    val isUsd: Boolean get() = code == USD_CODE

    fun fromUsd(usd: BigDecimal): BigDecimal = if (isUsd) usd else usd.multiply(perUsd, MathContext.DECIMAL64)

    /** A local amount in USD, rounded down to the cent. */
    fun toUsd(local: BigDecimal): BigDecimal = if (isUsd) local else local.divide(perUsd, 2, RoundingMode.DOWN)

    /** A USD amount in this currency, e.g. "€1,112.40" or "IDR 19,654,321.00". */
    fun format(usd: BigDecimal): String = formatLocal(fromUsd(usd))

    /** A guaranteed floor in this currency, rounded down. */
    fun formatAtLeast(usd: BigDecimal): String = formatLocal(fromUsd(usd), RoundingMode.DOWN)

    fun formatLocal(
        local: BigDecimal,
        rounding: RoundingMode = RoundingMode.HALF_UP,
    ): String {
        val number = DecimalFormat("#,##0.00", SYMBOLS).format(local.setScale(2, rounding))
        // A letter code ("IDR") takes a space; a sign ("$", "R$") doesn't.
        return if (symbol.lastOrNull()?.isLetter() == true) "$symbol $number" else "$symbol$number"
    }

    /** [usd] in this currency, rounded up to two significant figures. */
    fun presetFromUsd(usd: BigDecimal): BigDecimal {
        val local = fromUsd(usd)
        return if (isUsd) local else local.round(MathContext(PRESET_DIGITS, RoundingMode.CEILING)).stripTrailingZeros()
    }

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

/** The chosen currency's ZEC price over 1Click's USD price of ZEC, as the PAY balance converts. */
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
