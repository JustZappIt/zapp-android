package co.electriccoin.zcash.ui.screen.migration.progress

import co.electriccoin.zcash.ui.design.util.StringResource

data class MigrationProgressState(
    val title: StringResource,
    val subtitle: StringResource,
    // Feeds the "Split Balance" row shown above the transfer timeline — the split already
    // happened by the time this screen exists, so it's always "Done".
    val totalAmount: StringResource,
    val totalFiatAmount: StringResource? = null,
    val transfers: List<MigrationProgressTransferState>,
    val isComplete: Boolean,
    val hasOverdue: Boolean,
    val onBack: () -> Unit,
    val onSendNow: (() -> Unit)? = null,
    val onReschedule: (() -> Unit)? = null,
    val onDone: (() -> Unit)? = null,
)

data class MigrationProgressTransferState(
    val index: Int,
    val amount: StringResource,
    val statusLabel: StringResource,
    val isOverdue: Boolean,
    val isSent: Boolean,
    val fiatAmount: StringResource? = null,
)
