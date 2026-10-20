package co.electriccoin.zcash.ui.screen.invest.settings

import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestSettingsState(
    val country: StringResource,
    /** What the country means for Invest: buy and sell, sell only, or nothing until one is chosen. */
    val status: StringResource,
    val onCountryClick: () -> Unit,
)
