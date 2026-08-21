package co.electriccoin.zcash.ui.screen.unifiedsend

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.swap.SwapErrorFooterState

internal data class UnifiedSendState(
    // Asset selector — ZEC by default; picking another asset switches to swap mode
    val asset: AssetCardState,
    // Address field
    val address: TextFieldState,
    val addressPlaceholder: StringResource,
    val abContact: ChipButtonState?, // selected swap contact chip (swap mode)
    val abButton: IconButtonState,
    val qrButton: IconButtonState,
    // Swap mode only: opens the CrossPay explainer sheet
    val infoButton: IconButtonState?,
    val isABHintVisible: Boolean,
    val zecAmount: NumberTextFieldState,
    val fiatAmount: NumberTextFieldState,
    val fiatCurrency: FiatCurrency?,
    val isAmountSwapped: Boolean,
    val onAmountSwap: () -> Unit,
    val amountError: StringResource?,
    // Swap mode only: the destination side of the sentence. Typing in it makes the payment
    // exact-output ("the recipient gets exactly X"); typing on the pay side makes it exact-input.
    val theyReceive: TheyReceiveState?,
    // Exact-output only: the muted "≈ 0.42 ZEC" estimate standing in for the editable pay fields,
    // since what we actually spend is only known once NEAR quotes it.
    val payEstimate: PayEstimateState?,
    // Swap mode only: slippage tolerance button
    val slippage: StringResource?, // formatted text like "1%" — null in ZEC-direct mode
    val onSlippageClick: (() -> Unit)?,
    // ZEC-direct mode only: optional memo
    val memo: MemoFieldState?, // null in swap mode
    // Footers
    val amountErrorFooter: StringResource?,
    val errorFooter: SwapErrorFooterState?,
    val infoFooter: StringResource?,
    // Navigation
    val onBack: () -> Unit,
    val primaryButton: PrimaryButtonState,
)

/**
 * The destination amount row. The field is always editable — which side the user last typed into
 * is what decides between an exact-input and an exact-output quote.
 */
internal data class TheyReceiveState(
    val label: StringResource,
    val ticker: String,
    val amount: NumberTextFieldState,
    // Exact-output only: what the recipient's amount costs in USD, the currency invoices are quoted in
    val fiatEquivalent: StringResource?,
)

/**
 * The pay side while the destination amount is the binding one. Tapping it hands authority back,
 * mirroring the tap that gave the destination field authority in the first place.
 */
internal data class PayEstimateState(
    val text: StringResource,
    val onClick: () -> Unit,
)

internal enum class AddressMode { ZCASH, GENERIC }

internal sealed interface MemoFieldState {
    data class Editable(
        val text: String,
        val byteCount: Int,
        val maxBytes: Int,
        val isEnabled: Boolean,
        val onValueChange: (String) -> Unit,
    ) : MemoFieldState
}

internal sealed interface PrimaryButtonState {
    data class Review(
        val isLoading: Boolean,
        val onClick: () -> Unit
    ) : PrimaryButtonState

    data class TopUp(
        val onClick: () -> Unit
    ) : PrimaryButtonState

    object Disabled : PrimaryButtonState
}
