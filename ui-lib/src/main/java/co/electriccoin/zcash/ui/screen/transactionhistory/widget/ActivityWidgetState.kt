package co.electriccoin.zcash.ui.screen.transactionhistory.widget

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityState

sealed interface ActivityWidgetState {
    data class Data(
        val header: TransactionHistoryWidgetHeaderState,
        val transactions: List<ActivityState>
    ) : ActivityWidgetState

    data class Empty(
        val subtitle: StringResource?,
        val sendTransaction: ButtonState?,
        val enableShimmer: Boolean
    ) : ActivityWidgetState

    data object Loading : ActivityWidgetState
}
