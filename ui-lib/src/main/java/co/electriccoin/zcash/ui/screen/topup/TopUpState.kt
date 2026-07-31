package co.electriccoin.zcash.ui.screen.topup

import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState

internal data class TopUpState(
    override val onBack: () -> Unit,
    val onFromExchange: () -> Unit,
    val onFromWallet: () -> Unit,
) : ModalBottomSheetState
