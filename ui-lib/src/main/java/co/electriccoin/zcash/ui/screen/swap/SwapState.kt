package co.electriccoin.zcash.ui.screen.swap

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.listitem.SimpleListItemState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountTextFieldState
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountTextState

internal data class SwapState(
    val headerBalance: StringResource?,
    val headerBalanceFiat: StringResource?,
    val priceStats: SwapPriceStats?,
    val swapInfoButton: IconButtonState,
    val amountTextField: SwapAmountTextFieldState,
    val slippage: ButtonState,
    val amountText: SwapAmountTextState,
    val infoItems: List<SimpleListItemState>,
    val addressContact: ChipButtonState? = null,
    val addressBookButton: IconButtonState,
    val addressLocation: AddressLocation,
    val onAddressClick: (() -> Unit)?,
    val address: TextFieldState,
    val addressPlaceholder: StringResource,
    val qrScannerButton: IconButtonState,
    val infoFooter: StringResource?,
    val errorFooter: SwapErrorFooterState?,
    val primaryButton: ButtonState?,
    val topUpButton: ButtonState?,
    val onBack: () -> Unit,
    val changeModeButton: IconButtonState,
    val receivingZecAddress: StringResource?,
    val onChangeReceivingAddress: (() -> Unit)?,
) {
    enum class AddressLocation {
        TOP,
        BOTTOM
    }
}

data class SwapPriceStats(
    val price: StringResource,
    val fee: StringResource,
)

data class SwapErrorFooterState(
    val title: StringResource,
    val subtitle: StringResource,
)

internal data class SwapCancelState(
    val icon: ImageResource,
    val title: StringResource,
    val subtitle: StringResource,
    val negativeButton: ButtonState,
    val positiveButton: ButtonState,
    override val onBack: () -> Unit,
) : ModalBottomSheetState
