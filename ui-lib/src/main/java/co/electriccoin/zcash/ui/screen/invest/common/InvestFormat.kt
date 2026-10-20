package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The number shapes Invest shows, in the app's fixed number locale like every other money figure. */
internal object InvestFormat {
    private val symbols = DecimalFormatSymbols(Locale.US)

    /** "0.4410 NVDA", rounded down so it never shows more than is held. */
    fun units(
        units: BigDecimal,
        ticker: String,
    ): String =
        DecimalFormat("#,##0.0000", symbols).format(units.setScale(UNIT_SCALE, RoundingMode.DOWN)) +
            " $ticker"

    /** "0.06478 ZEC": up to five places. */
    fun zec(zec: BigDecimal): String =
        zec
            .setScale(ZEC_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .let { if (it.scale() < 0) it.setScale(0) else it }
            .toPlainString() + " ZEC"

    /** "0.95" for 0.95 %. */
    fun percent(value: BigDecimal): String =
        value
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    /** Whole minutes, rounded up; null without an estimate. */
    fun etaMinutes(seconds: Int?): Int? {
        if (seconds == null || seconds <= 0) return null
        return ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1)
    }

    /** "9:42". */
    fun countdown(seconds: Long): String = "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

    /** "Mon 21:30" in the phone's time zone and language. */
    fun localDayTime(instant: Instant): String =
        DateTimeFormatter
            .ofPattern(DAY_TIME_PATTERN, Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)

    fun monogram(ticker: String): String = ticker.take(2).uppercase()

    /** "Germany" for "DE", in the phone's language. */
    fun countryName(code: String): String =
        Locale
            .Builder()
            .setRegion(code)
            .build()
            .getDisplayCountry(Locale.getDefault())

    private const val DAY_TIME_PATTERN = "EEE HH:mm"
    private const val UNIT_SCALE = 4
    private const val ZEC_SCALE = 5
    private const val SECONDS_PER_MINUTE = 60
}

internal fun Throwable.toInvestMessage(): StringResource =
    when (this) {
        is InvestApiException.ClockSkew -> stringRes(R.string.invest_error_clock)
        is InvestApiException.TorUnavailable -> stringRes(R.string.invest_error_tor)
        is InvestApiException.Unreachable -> stringRes(R.string.invest_error_unreachable)
        else -> stringRes(R.string.invest_error_generic)
    }
