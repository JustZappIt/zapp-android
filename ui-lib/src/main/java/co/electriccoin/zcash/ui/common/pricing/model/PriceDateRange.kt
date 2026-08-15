package co.electriccoin.zcash.ui.common.pricing.model

import java.time.LocalDate

data class PriceDateRange(
    val from: LocalDate,
    val to: LocalDate,
) {
    init {
        require(!from.isAfter(to)) { "from must not be after to" }
    }
}
