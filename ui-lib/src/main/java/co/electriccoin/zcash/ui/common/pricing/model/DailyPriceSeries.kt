package co.electriccoin.zcash.ui.common.pricing.model

import cash.z.ecc.android.sdk.model.FiatCurrency
import java.time.Instant
import java.time.LocalDate

data class DailyPriceSeries(
    val fiatCurrency: FiatCurrency,
    val points: List<DailyFiatPrice>,
    val availableFrom: LocalDate,
    val availableTo: LocalDate,
    val dataAsOf: Instant,
)
