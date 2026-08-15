package co.electriccoin.zcash.ui.common.pricing.model

import java.math.BigDecimal
import java.time.LocalDate

data class DailyFiatPrice(
    val date: LocalDate,
    val fiatPerZec: BigDecimal,
)
