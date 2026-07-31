package co.electriccoin.zcash.ui.screen.insufficientfunds

import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState

data class InsufficientFundsState(
    override val onBack: () -> Unit,
    val onAddZec: (() -> Unit)? = null,
) : ModalBottomSheetState
