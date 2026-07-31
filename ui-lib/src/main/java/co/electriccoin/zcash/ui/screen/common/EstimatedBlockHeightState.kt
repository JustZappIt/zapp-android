package co.electriccoin.zcash.ui.screen.common

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle

data class EstimatedBlockHeightState(
    val title: StringResource?,
    val subtitle: StringResource = stringRes(R.string.wbh_estimation_subtitle),
    val message: StyledStringResource = stringRes(R.string.wbh_estimation_message).withStyle(),
    val logo: Int?,
    val blockHeightText: StringResource,
    val onBack: () -> Unit,
    val dialogButton: IconButtonState,
    val copyButton: ButtonState,
    val primaryButton: ButtonState,
)
