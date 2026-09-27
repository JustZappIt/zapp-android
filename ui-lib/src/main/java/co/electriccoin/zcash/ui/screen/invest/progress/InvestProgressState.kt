package co.electriccoin.zcash.ui.screen.invest.progress

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestProgressState(
    val title: StringResource,
    val subtitle: StringResource?,
    /** Held privately: the title renders as the success header. */
    val isSuccess: Boolean,
    val steps: List<ZappStep>,
    /** The danger card for a buy 1Click marked FAILED, with the reference support needs. */
    val attention: StringResource?,
    /** Checking the status failed; the buy itself carries on regardless. */
    val checkError: StringResource?,
    val onCheckAgain: () -> Unit,
    /** "Back to Pay". */
    val primaryButton: ButtonState,
    /** "Remove from list", once a buy needs attention and its reference is on screen. */
    val removeButton: ButtonState?,
    val onBack: () -> Unit,
)
