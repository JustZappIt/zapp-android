package co.electriccoin.zcash.ui.screen.migration.progress

import co.electriccoin.zcash.ui.design.util.StringResource

data class MigrationProgressState(
    val title: StringResource,
    val subtitle: StringResource,
    // Feeds the "Split Balance N" rows shown above the transfer timeline — one row per
    // note-split (preparation) transaction, rendered in broadcast order.
    val totalAmount: StringResource,
    val totalFiatAmount: StringResource? = null,
    val preparations: List<MigrationProgressPreparationState> = emptyList(),
    val transfers: List<MigrationProgressTransferState>,
    val isComplete: Boolean,
    val onBack: () -> Unit,
    val onDone: (() -> Unit)? = null,
)

/**
 * One note-split (preparation) transaction row in the Migration Progress timeline.
 *
 * [number] is 1-based display order (broadcast/schedule order).
 * [statusLabel] uses the same relative formatting as transfer rows ("Sent X min ago" / "~X min").
 * [syncLabel] is non-null only in DEBUG builds — shows the prove state as a relative time label,
 * appended to [statusLabel] in the UI as "· sync $syncLabel" when present.
 */
data class MigrationProgressPreparationState(
    val number: Int,
    val statusLabel: StringResource,
    val isSent: Boolean,
    val syncLabel: StringResource? = null,
)

data class MigrationProgressTransferState(
    val index: Int,
    val amount: StringResource,
    val statusLabel: StringResource,
    // Attention paint (orange) — genuine cannot-heal states only (expired / unprovable anchor),
    // never a merely-late-but-healthy transfer.
    val isAttention: Boolean,
    val isSent: Boolean,
    val fiatAmount: StringResource? = null,
    // Non-null only in DEBUG builds — shows the prove state ("proved" / relative time / "pending"),
    // appended to [statusLabel] in the UI as "· sync $syncLabel" when present.
    val syncLabel: StringResource? = null,
)
