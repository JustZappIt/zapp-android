package co.electriccoin.zcash.ui.screen.invest.intro

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestIntroState(
    val primaryButton: ButtonState,
    val hint: StringResource,
    val errorText: StringResource?,
    val onBack: () -> Unit,
)
