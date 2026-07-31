package co.electriccoin.zcash.ui.screen.hotfix.ephemeral

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource

data class EphemeralHotfixState(
    val title: StringResource,
    val message: StringResource,
    val subtitle: StringResource,
    val address: TextFieldState,
    val button: ButtonState,
    val info: StringResource?,
    override val onBack: () -> Unit,
) : ModalBottomSheetState
