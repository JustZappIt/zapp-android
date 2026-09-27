package co.electriccoin.zcash.ui.screen.invest.buy

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource

/** I5: the amount to invest, with a live dry quote under it. */
internal data class InvestBuyState(
    /** "Buy NVIDIA". */
    val title: StringResource,
    /** The amount's currency sign: the user's own currency, or "$" without an exchange rate. */
    val currencySymbol: String,
    /** The amount in [currencySymbol]'s currency; the VM asks 1Click in USD. */
    val amountInput: NumberTextFieldState,
    /** "0.7806 ZEC · $1,204.87": the spendable shielded balance the buy is paid from. */
    val balanceText: StringResource,
    val presets: List<InvestPresetState>,
    /** The settlement ledger; null while the no-price card stands in for it. */
    val ledger: InvestBuyLedger?,
    val noPrice: InvestNoPriceState?,
    /** Under the ledger: "Getting a price…", the minimum, not enough ZEC, or a failed quote. */
    val notice: StringResource?,
    val isNoticeDanger: Boolean,
    /** Red underline on the amount (more than the shielded balance covers). */
    val isAmountError: Boolean,
    /** "Review": enabled only with a priced estimate. */
    val primaryButton: ButtonState,
    val isPreparing: Boolean,
    val onBack: () -> Unit,
)

internal data class InvestPresetState(
    val label: StringResource,
    val isEnabled: Boolean,
    val onClick: () -> Unit,
)

/** Value first, then units, as everywhere in Invest. A null value renders as the pending dash. */
internal data class InvestBuyLedger(
    val youSend: StringResource?,
    val youGet: StringResource?,
    val fees: StringResource?,
    val eta: StringResource?,
)

/** "No price right now": 1Click had no liquidity. The amount is kept and nothing is sent. */
internal data class InvestNoPriceState(
    val body: StringResource,
    /** "Weekday trading reopens Mon 02:00", for a weekday stock outside its window. */
    val reopen: StringResource?,
    val isRetrying: Boolean,
    val onTryAgain: () -> Unit,
)

/** I6: the review sheet over I5, built from a live quote that holds its price until [countdown] runs out. */
internal data class InvestReviewState(
    val youSend: StringResource,
    val atLeast: StringResource,
    val expected: StringResource,
    val fees: StringResource,
    /** "9:42", or "Expired" once the price is no longer held. */
    val countdown: StringResource,
    val isExpired: Boolean,
    val privacy: StringResource,
    /** "Confirm and buy", or "Refresh price" once the countdown reaches zero. */
    val primaryButton: ButtonState,
    val isBusy: Boolean,
    val errorText: StringResource?,
    val onDismiss: () -> Unit,
)
