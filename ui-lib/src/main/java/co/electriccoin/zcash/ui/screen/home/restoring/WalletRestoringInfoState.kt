package co.electriccoin.zcash.ui.screen.home.restoring

import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.StringResource

data class WalletRestoringInfoState(
    val info: StringResource?,
    override val onBack: () -> Unit
) : ModalBottomSheetState
