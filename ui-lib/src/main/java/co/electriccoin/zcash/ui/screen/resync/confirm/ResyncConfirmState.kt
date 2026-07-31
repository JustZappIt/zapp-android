package co.electriccoin.zcash.ui.screen.resync.confirm

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource

data class ResyncConfirmState(
    val title: StringResource,
    val subtitle: StringResource,
    val message: StringResource,
    val change: ButtonState,
    val changeInfo: StyledStringResource,
    val confirm: ButtonState,
    val onBack: () -> Unit,
)
