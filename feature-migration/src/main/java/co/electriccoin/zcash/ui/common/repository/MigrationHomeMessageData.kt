package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan

/**
 * The migration home-banner payload (formerly ui-lib's `HomeMessageData.Migration`) — the concrete
 * [MigrationHomeMessage] the feature module emits through `MigrationHomeMessageSource`.
 */
data class MigrationHomeMessageData(
    val plan: MigrationPlan?,
    val isComplete: Boolean = false,
    // Spec §6.4 "Transfer Ready to Send": true when [plan]'s next pending transfer's scheduled
    // time has arrived, background execution is unavailable, and the SDK doesn't yet count it
    // as overdue — a narrower, earlier window than the general missed-transfer/overdue state.
    // See migrationMessageFor() in MigrationHomeMessageSourceImpl.kt for the derivation.
    val isReadyToSend: Boolean = false,
    // Non-null exactly when the SDK's MigrationState is RequiresAttention (spec §6.2/§6.3) —
    // see MigrationAttentionKind's doc for why the two causes must never collapse into one
    // generic message again. attentionRangeText is only meaningful for TRANSFER_EXPIRED (the
    // specific "Transfer 3–5" range that actually expired); null for PLAN_UPDATE, whose home
    // message doesn't name a range (see design spec §6.2, no range mentioned there).
    val attentionKind: MigrationAttentionKind? = null,
    val attentionRangeText: String? = null,
) : MigrationHomeMessage()
