package co.electriccoin.zcash.ui.screen.swap.upi.bridge

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.swap.upi.progress.UpiOfframpStep

internal data class BridgeToBaseState(
    val amountInput: NumberTextFieldState,
    val baseBalanceText: StringResource?,
    val usdcEquivalentText: StringResource?,
    val zecToSendText: StringResource?,
    val isInsufficient: Boolean,
    val insufficientText: StringResource?,
    val feeText: StringResource?,
    val slippageText: StringResource?,
    val quoteStatusText: StringResource?,
    val etaValueText: StringResource?,
    val etaText: StringResource?,
    /** Warning shown only when no merchant currently has fiat liquidity; null when some are available. */
    val unavailableText: StringResource?,
    val errorText: StringResource?,
    /** "Adding ≈ ₹Y · X USDC" shown once bridging starts, so the amount is always visible. */
    val bridgingAmountText: StringResource?,
    /** Empty while the user is still entering an amount; the small bridge step list once it starts. */
    val steps: List<UpiOfframpStep>,
    val isInputVisible: Boolean,
    val primaryButton: ButtonState,
    val onBack: () -> Unit,
)
