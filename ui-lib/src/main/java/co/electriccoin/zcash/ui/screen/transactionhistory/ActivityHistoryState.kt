package co.electriccoin.zcash.ui.screen.transactionhistory

import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.Itemizable
import co.electriccoin.zcash.ui.design.util.StringResource
import java.util.UUID

sealed interface ActivityHistoryState {
    val onBack: () -> Unit
    val filterButton: IconButtonState

    data class Loading(
        override val onBack: () -> Unit,
        override val filterButton: IconButtonState
    ) : ActivityHistoryState

    data class Empty(
        override val onBack: () -> Unit,
        override val filterButton: IconButtonState
    ) : ActivityHistoryState

    data class Data(
        override val onBack: () -> Unit,
        override val filterButton: IconButtonState,
        val items: List<ActivityHistoryItem>,
        val filtersId: String = UUID.randomUUID().toString()
    ) : ActivityHistoryState
}

sealed interface ActivityHistoryItem {
    data class Header(
        val title: StringResource,
        override val key: Any = UUID.randomUUID()
    ) : ActivityHistoryItem,
        Itemizable {
        override val contentType = "Transaction Header"
    }

    data class Activity(
        val state: ActivityState
    ) : ActivityHistoryItem
}
