package co.electriccoin.zcash.ui.screen.advancedsettings.debug.db

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource

data class DebugDBState(
    val query: TextFieldState,
    val output: StringResource,
    val execute: ButtonState,
    val onBack: () -> Unit,
)
