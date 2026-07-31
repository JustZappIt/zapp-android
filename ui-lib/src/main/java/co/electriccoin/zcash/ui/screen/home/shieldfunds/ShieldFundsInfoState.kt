package co.electriccoin.zcash.ui.screen.home.shieldfunds

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.StringResource

data class ShieldFundsInfoState(
    val subtitle: StringResource,
    override val onBack: () -> Unit,
    val checkbox: CheckboxState,
    val primaryButton: ButtonState,
    val secondaryButton: ButtonState,
) : ModalBottomSheetState
