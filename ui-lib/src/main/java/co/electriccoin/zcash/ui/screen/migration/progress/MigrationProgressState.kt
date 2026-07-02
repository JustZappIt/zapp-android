package co.electriccoin.zcash.ui.screen.migration.progress

import co.electriccoin.zcash.ui.design.util.StringResource

data class MigrationProgressState(
    val title: StringResource,
    val subtitle: StringResource,
    val transfers: List<MigrationProgressTransferState>,
    val isComplete: Boolean,
    val hasOverdue: Boolean,
    val onBack: () -> Unit,
    val onSendNow: (() -> Unit)? = null,
    val onReschedule: (() -> Unit)? = null,
    val onSimulateTransfer: (() -> Unit)? = null,
    val onDone: (() -> Unit)? = null,
    val sendNowFailureSheet: MigrationSendNowFailureState? = null,
)

data class MigrationProgressTransferState(
    val index: Int,
    val amount: StringResource,
    val statusLabel: StringResource,
    val isOverdue: Boolean,
    val isSent: Boolean,
    val fiatAmount: StringResource? = null,
)

data class MigrationSendNowFailureState(
    val message: String,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)
