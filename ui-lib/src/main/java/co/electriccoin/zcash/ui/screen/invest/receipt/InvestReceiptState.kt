package co.electriccoin.zcash.ui.screen.invest.receipt

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestReceiptState(
    val title: StringResource,
    /** The shares at today's price; null unless held and priced. */
    val value: StringResource?,
    val units: StringResource?,
    val status: StringResource,
    val isStatusDanger: Boolean,
    val fees: StringResource?,
    val date: StringResource?,
    /** The deposit address, which support asks for. */
    val reference: String,
    val isSupportOpen: Boolean,
    val onToggleSupport: () -> Unit,
    val onCopyReference: () -> Unit,
    val contactSupportButton: ButtonState?,
    val progressButton: ButtonState?,
    val onBack: () -> Unit,
)
