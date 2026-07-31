package co.electriccoin.zcash.ui.screen.swap.upi

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import xyz.justzappit.offramp.p2p.CurrencyCode

internal data class UpiOfframpState(
    val inrInput: NumberTextFieldState,
    val currency: CurrencyCode = CurrencyCode.Inr,
    val fiatAmountText: StringResource? = null,
    val usdcEquivalent: StringResource?,
    val rateText: StringResource,
    val errorText: StringResource?,
    val sendButton: ButtonState,
    val onHistoryClick: () -> Unit,
    val onAddFunds: () -> Unit,
    val baseBalanceText: StringResource? = null,
    val fundingPlanText: StringResource? = null,
    val isTopUpNeeded: Boolean = false,
    /** Forgets an in-flight checkpoint locally, without touching the on-chain order. */
    val onDiscardInFlight: (() -> Unit)? = null,
)
