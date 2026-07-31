package co.electriccoin.zcash.ui.screen.error

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState

data class SyncErrorState(
    val tryAgain: ButtonState,
    val switchServer: ButtonState,
    val disableTor: ButtonState?,
    val support: ButtonState,
    override val onBack: () -> Unit
) : ModalBottomSheetState
