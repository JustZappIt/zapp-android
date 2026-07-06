package co.electriccoin.zcash.ui.screen.migration.progress

import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
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
    val onDone: (() -> Unit)? = null,
    val sendNowFailureSheet: MigrationTransferFailureState? = null,
)

data class MigrationProgressTransferState(
    val index: Int,
    val amount: StringResource,
    val statusLabel: StringResource,
    val isOverdue: Boolean,
    val isSent: Boolean,
    val fiatAmount: StringResource? = null,
)
