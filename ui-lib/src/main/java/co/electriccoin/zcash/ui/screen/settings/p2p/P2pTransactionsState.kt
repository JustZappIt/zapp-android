package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.annotation.DrawableRes
import co.electriccoin.zcash.ui.common.model.P2pProvider
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource

data class P2pTransactionsState(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val isRefreshing: Boolean,
    val balance: BalanceState,
    val refund: RefundUiState,
    val confirmRefund: ConfirmRefundDialog?,
    /** Null when only one kind of activity can exist on this build, which is every non-mainnet one. */
    val filter: FilterState?,
    val rows: List<P2pTransactionRow>,
    val emptyMessage: StringResource?,
    val errorMessage: StringResource?,
    val confirmation: ZappConfirmationState?,
)

sealed interface BalanceState {
    data object Loading : BalanceState

    data class Loaded(
        val balanceUsdc: StringResource,
        val accountAddressShort: String,
        val accountExplorerUrl: String?,
    ) : BalanceState

    data object Unavailable : BalanceState
}

/**
 * Drives the inline Refund control next to the balance. `Hidden` covers every case the user
 * shouldn't see the button: testnet (no NEAR route), zero balance, balance still loading.
 */
sealed interface RefundUiState {
    data object Hidden : RefundUiState

    data class Available(
        val onClick: () -> Unit
    ) : RefundUiState

    /** A cash-out has a claim on this balance, and the refund moves all of it or none. */
    data class Blocked(
        val reason: StringResource
    ) : RefundUiState

    data object InProgress : RefundUiState

    data class FailedRetry(
        val message: StringResource,
        val onRetry: () -> Unit
    ) : RefundUiState
}

data class ConfirmRefundDialog(
    val amount: StringResource,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

data class FilterState(
    val options: List<P2pActivityFilter>,
    val selected: P2pActivityFilter,
    val onSelect: (P2pActivityFilter) -> Unit,
)

enum class P2pActivityFilter { ALL, PEER, P2P_ME }

/**
 * One activity, whichever provider it came from. A cash-out and a merchant payment are the same
 * shape on screen: what it was, how it went, how much, and an expandable panel of specifics.
 */
data class P2pTransactionRow(
    val key: String,
    val provider: P2pProvider,
    @param:DrawableRes val logo: Int?,
    val typeLabel: StringResource,
    val statusLabel: StringResource,
    val statusTone: StatusTone,
    val amountUsdc: StringResource,
    val amountSecondary: StringResource?,
    val reference: StringResource?,
    val referenceUrl: String?,
    val timestamp: StringResource?,
    val detail: TransactionDetail?,
) {
    enum class StatusTone { Pending, Success, Cancelled, Failed }
}

/** Revealed when the user expands a row: the specifics, then whatever can still be done about it. */
data class TransactionDetail(
    val rows: List<TransactionDetailRow>,
    val actions: List<ButtonState> = emptyList(),
)

data class TransactionDetailRow(
    val label: StringResource,
    val value: StringResource,
    val url: String? = null,
)
