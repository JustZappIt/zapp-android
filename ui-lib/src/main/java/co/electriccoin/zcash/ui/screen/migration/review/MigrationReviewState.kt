package co.electriccoin.zcash.ui.screen.migration.review

import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.design.util.StringResource

data class MigrationReviewState(
    val mode: MigrationMode,
    val totalAmount: StringResource,
    val estimatedDuration: StringResource,
    val transfers: List<MigrationReviewTransferState>,
    val isKeystone: Boolean = false,
    // Only populated for MigrationMode.IMMEDIATE — the single-transfer flow shows a fee line on
    // its Details card. AUTOMATIC's PrivacyReviewContent doesn't use this field.
    val fee: StringResource? = null,
    val isConfirming: Boolean = false,
    val onConfirm: () -> Unit,
    val onBack: () -> Unit,
    // Only ever set for AUTOMATIC + MANUAL delivery, where confirming sends transfer #1
    // synchronously in the foreground and can fail right here on this screen.
    val failureSheet: MigrationTransferFailureState? = null,
)

data class MigrationReviewTransferState(
    val index: Int,
    val totalCount: Int,
    val amount: StringResource,
    val fiatAmount: StringResource?,
    val scheduledLabel: StringResource,
)
