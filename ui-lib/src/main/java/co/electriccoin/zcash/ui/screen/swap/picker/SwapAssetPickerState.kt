package co.electriccoin.zcash.ui.screen.swap.picker

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.home.common.CommonErrorScreenState

data class SwapAssetPickerState(
    val title: StringResource,
    val search: TextFieldState,
    val data: SwapAssetPickerDataState,
    override val onBack: () -> Unit,
) : ModalBottomSheetState

sealed interface SwapAssetPickerDataState {
    data object Loading : SwapAssetPickerDataState

    data class Success(
        val items: List<ListItemState>
    ) : SwapAssetPickerDataState

    data class Error(
        override val title: StringResource,
        override val subtitle: StringResource,
        override val buttonState: ButtonState
    ) : SwapAssetPickerDataState,
        CommonErrorScreenState
}
