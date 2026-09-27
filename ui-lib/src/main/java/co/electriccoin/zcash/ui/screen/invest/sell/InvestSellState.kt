package co.electriccoin.zcash.ui.screen.invest.sell

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.invest.buy.InvestNoPriceState
import co.electriccoin.zcash.ui.screen.invest.buy.InvestPresetState
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressState

/** What the amount is typed in: money (the user's currency) or shares. */
enum class SellAmountMode { MONEY, SHARES }

/** I8: how much of a holding to sell, with a live dry quote under it. */
internal data class InvestSellState(
    /** "Sell NVIDIA". */
    val title: StringResource,
    val mode: SellAmountMode,
    /** The currency sign in money mode, the ticker in shares mode. */
    val amountSymbol: String,
    val amountInput: NumberTextFieldState,
    /** "You hold": value first, shares second. */
    val holdingText: StringResource,
    val onModeChange: (SellAmountMode) -> Unit,
    /** 50 % and Sell all. */
    val presets: List<InvestPresetState>,
    val ledger: InvestSellLedger?,
    val noPrice: InvestNoPriceState?,
    /** Under the ledger: loading, minimum, more than held, nothing held, or a failed quote. */
    val notice: StringResource?,
    val isNoticeDanger: Boolean,
    val isAmountError: Boolean,
    /** "This would leave less than $40. Sell all instead?", with the button that does it. */
    val sellAllSuggestion: InvestSellAllSuggestion?,
    val primaryButton: ButtonState,
    val isPreparing: Boolean,
    /** Set when the generated intent wasn't the reviewed transfer: the screen says so and nothing else. */
    val isRefused: Boolean,
    /** A buy or sale of this stock is still pending, so Review is off; links to it. */
    val tradeInProgress: InvestTradeInProgressState?,
    val onBack: () -> Unit,
)

internal data class InvestSellLedger(
    /** Value first, then shares. */
    val youSell: StringResource?,
    val youGet: StringResource?,
    val fees: StringResource?,
    /** The fixed ZEC withdrawal fee, and a nudge to sell more at once when it is a big share of the sale. */
    val feeNote: StringResource?,
    val eta: StringResource?,
)

internal data class InvestSellAllSuggestion(
    val text: StringResource,
    val onSellAll: () -> Unit,
)

/** I9: "What you're authorising", built from the prepared intent, held until [countdown] runs out. */
internal data class InvestSellReviewState(
    /** The one exception to value-first: the signed message moves an exact number of shares. */
    val authorisation: StringResource,
    val atLeast: StringResource,
    val expected: StringResource,
    val fees: StringResource,
    /** "12:58"-style time left, or "Expired". */
    val countdown: StringResource,
    val isExpired: Boolean,
    val signedMessage: String,
    val isSignedMessageOpen: Boolean,
    val onToggleSignedMessage: () -> Unit,
    /** "Confirm and sell", or "Refresh price" once the countdown reaches zero. */
    val primaryButton: ButtonState,
    val isBusy: Boolean,
    val errorText: StringResource?,
    val onDismiss: () -> Unit,
)
