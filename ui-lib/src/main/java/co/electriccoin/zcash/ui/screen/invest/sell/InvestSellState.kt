package co.electriccoin.zcash.ui.screen.invest.sell

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.invest.common.InvestNoPriceState
import co.electriccoin.zcash.ui.screen.invest.common.InvestPresetState
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressState

internal enum class SellAmountMode { MONEY, SHARES }

internal data class InvestSellState(
    val title: StringResource,
    val mode: SellAmountMode,
    /** The currency sign in money mode, the ticker in shares mode. */
    val amountSymbol: String,
    val amountInput: NumberTextFieldState,
    val holdingText: StringResource,
    val onModeChange: (SellAmountMode) -> Unit,
    val presets: List<InvestPresetState>,
    val ledger: InvestSellLedger?,
    val noPrice: InvestNoPriceState?,
    val notice: StringResource?,
    val isNoticeDanger: Boolean,
    val isAmountError: Boolean,
    /** Offered when a partial sale would leave less than the minimum behind. */
    val sellAllSuggestion: InvestSellAllSuggestion?,
    val primaryButton: ButtonState,
    /** The generated intent wasn't the reviewed transfer; the screen says only that. */
    val isRefused: Boolean,
    val tradeInProgress: InvestTradeInProgressState?,
    val onBack: () -> Unit,
)

internal data class InvestSellLedger(
    val youSell: StringResource?,
    val youGet: StringResource?,
    val fees: StringResource?,
    /** The fixed ZEC withdrawal fee, with a nudge to sell more at once when it is a big share. */
    val feeNote: StringResource?,
    val eta: StringResource?,
)

internal data class InvestSellAllSuggestion(
    val text: StringResource,
    val onSellAll: () -> Unit,
)

internal data class InvestSellReviewState(
    /** What the key is about to authorise, in words. */
    val authorisation: StringResource,
    val atLeast: StringResource,
    val expected: StringResource,
    val fees: StringResource,
    val countdown: StringResource,
    val isExpired: Boolean,
    val signedMessage: String,
    val isSignedMessageOpen: Boolean,
    val onToggleSignedMessage: () -> Unit,
    /** "Confirm and sell", or "Refresh price" once the price has expired. */
    val primaryButton: ButtonState,
    val isBusy: Boolean,
    val errorText: StringResource?,
    val onDismiss: () -> Unit,
)
