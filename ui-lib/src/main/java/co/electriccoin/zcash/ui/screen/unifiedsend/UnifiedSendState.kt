package co.electriccoin.zcash.ui.screen.unifiedsend

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ButtonState
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
    // Swap mode only: slippage tolerance button — null in ZEC-direct mode
    val slippage: ButtonState?,
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
 * The destination amount row: one field, denominated either in the destination token or in USD — the
 * currency the asset is priced in, so it stays comparable with an invoice. [onSwapCurrency] flips
 * between the two, mirroring the same toggle on the pay side; it is null when the asset carries no USD
 * price and there is nothing to convert with. Typing here is what makes the payment exact-output; in
 * exact-input the field simply carries the estimate.
 */
internal data class TheyReceiveState(
    val label: StringResource,
    val unit: String,
    val amount: NumberTextFieldState,
    val onSwapCurrency: (() -> Unit)?,
)

/**
 * The pay side while the destination amount is the binding one. Tapping it hands authority back,
 * mirroring the tap that gave the destination field authority in the first place.
 */
internal data class PayEstimateState(
    val text: StringResource,
    val onClick: (() -> Unit)?,
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

/**
 * Mirrors the four states upstream's Pay screen gives its primary button (review / try again /
 * loading / disabled), plus the Top Up call to action Zapp offers instead of leaving the user on a
 * dead button when they cannot cover the payment.
 */
internal sealed interface PrimaryButtonState {
    data class Review(
        val isLoading: Boolean,
        val onClick: () -> Unit
    ) : PrimaryButtonState

    /** Swap assets failed to load. Retries the fetch rather than submitting anything. */
    data class Retry(
        val isLoading: Boolean,
        val onClick: () -> Unit
    ) : PrimaryButtonState

    /** The first swap-asset fetch is still in flight, so there is nothing to review yet. */
    data object Loading : PrimaryButtonState

    data class TopUp(
        val onClick: () -> Unit
    ) : PrimaryButtonState

    data object Disabled : PrimaryButtonState
}
