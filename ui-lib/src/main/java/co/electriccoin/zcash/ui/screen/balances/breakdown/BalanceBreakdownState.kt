package co.electriccoin.zcash.ui.screen.balances.breakdown

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.StringResource

data class BalanceBreakdownState(
    val title: StringResource,
    val subtitle: StringResource,
    val total: BalanceBreakdownItemState,
    val pools: List<BalanceBreakdownItemState>,
    /** Null in builds without private USD. */
    val privateUsd: BalanceBreakdownPrivateUsdState? = null,
    val positive: ButtonState,
    override val onBack: () -> Unit,
) : ModalBottomSheetState

data class BalanceBreakdownItemState(
    val title: StringResource,
    val amount: Zatoshi,
    /** Fiat equivalent; `null` when currency conversion is disabled or unavailable. */
    val fiat: StringResource?,
)

data class BalanceBreakdownPrivateUsdState(
    val amount: StringResource,
    val detail: StringResource?,
    /** Screening refused some of it. */
    val isBlocked: Boolean,
    val onClick: () -> Unit,
)
