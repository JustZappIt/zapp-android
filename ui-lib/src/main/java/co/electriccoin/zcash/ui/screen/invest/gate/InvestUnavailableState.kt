package co.electriccoin.zcash.ui.screen.invest.gate

import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestUnavailableState(
    val body: StringResource,
    val onChangeCountry: () -> Unit,
    val onDone: () -> Unit,
)
