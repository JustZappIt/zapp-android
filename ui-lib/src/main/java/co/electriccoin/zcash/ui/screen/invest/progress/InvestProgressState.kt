package co.electriccoin.zcash.ui.screen.invest.progress

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestProgressState(
    val title: StringResource,
    val subtitle: StringResource?,
    /** The title renders as the success header. */
    val isSuccess: Boolean,
    val steps: List<ZappStep>,
    /** The reference support needs, for a trade that needs attention. */
    val attention: StringResource?,
    /** Checking the status failed; the trade itself carries on. */
    val checkError: StringResource?,
    val onCheckAgain: () -> Unit,
    val primaryButton: ButtonState,
    val contactSupportButton: ButtonState?,
    val removeButton: ButtonState?,
    val onBack: () -> Unit,
)
