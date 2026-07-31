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
    val isABHintVisible: Boolean,
    val zecAmount: NumberTextFieldState,
    val fiatAmount: NumberTextFieldState,
    val fiatCurrency: FiatCurrency?,
    val isAmountSwapped: Boolean,
    val onAmountSwap: () -> Unit,
    val amountError: StringResource?,
    // Swap mode only: estimated output amount ("They receive ≈ X TOKEN")
    val theyReceiveLabel: StringResource?,
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
