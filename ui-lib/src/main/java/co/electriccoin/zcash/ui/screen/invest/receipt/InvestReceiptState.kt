package co.electriccoin.zcash.ui.screen.invest.receipt

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestReceiptState(
    /** "Bought NVIDIA", or the buy's current outcome while it isn't one. */
    val title: StringResource,
    /** Value first: the shares at today's price. Null unless the stock is held and priced. */
    val value: StringResource?,
    /** Shares second. */
    val units: StringResource?,
    val status: StringResource,
    val isStatusDanger: Boolean,
    val fees: StringResource?,
    val date: StringResource?,
    /** The deposit address, which is the reference support asks for. */
    val reference: String,
    val isSupportOpen: Boolean,
    val onToggleSupport: () -> Unit,
    val onCopyReference: () -> Unit,
    /** "Contact support", with the reference in the draft, when the buy needs attention. */
    val contactSupportButton: ButtonState?,
    /** "See progress" while the buy hasn't finished. */
    val progressButton: ButtonState?,
    val onBack: () -> Unit,
)
