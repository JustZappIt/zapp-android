package co.electriccoin.zcash.ui.screen.invest.buy

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.invest.common.InvestNoPriceState
import co.electriccoin.zcash.ui.screen.invest.common.InvestPresetState
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressState

internal data class InvestBuyState(
    val title: StringResource,
    /** The user's currency, or "$" without an exchange rate. */
    val currencySymbol: String,
    val amountInput: NumberTextFieldState,
    val balanceText: StringResource,
    val presets: List<InvestPresetState>,
    /** Null while the no-price card stands in for it. */
    val ledger: InvestBuyLedger?,
    val noPrice: InvestNoPriceState?,
    val notice: StringResource?,
    val isNoticeDanger: Boolean,
    val isAmountError: Boolean,
    val primaryButton: ButtonState,
    val tradeInProgress: InvestTradeInProgressState?,
    /** Buying isn't offered in the saved country. */
    val sellOnly: InvestTradeInProgressState? = null,
    val onBack: () -> Unit,
)

/** A null value renders as the pending dash. */
internal data class InvestBuyLedger(
    val youSend: StringResource?,
    val youGet: StringResource?,
    val fees: StringResource?,
    val eta: StringResource?,
)

internal data class InvestReviewState(
    val youSend: StringResource,
    val atLeast: StringResource,
    val expected: StringResource,
    val fees: StringResource,
    /** "9:42", or "Expired" once the price is no longer held. */
    val countdown: StringResource,
    val isExpired: Boolean,
    val privacy: StringResource,
    /** "Confirm and buy", or "Refresh price" once the price has expired. */
    val primaryButton: ButtonState,
    val isBusy: Boolean,
    val errorText: StringResource?,
    val onDismiss: () -> Unit,
)
