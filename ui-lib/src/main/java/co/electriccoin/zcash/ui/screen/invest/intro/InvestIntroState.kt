package co.electriccoin.zcash.ui.screen.invest.intro

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestIntroState(
    /** "Set up Invest", or "Back up recovery phrase first" until the phrase is backed up. */
    val primaryButton: ButtonState,
    val isSettingUp: Boolean,
    val hint: StringResource,
    val errorText: StringResource?,
    val onBack: () -> Unit,
)
