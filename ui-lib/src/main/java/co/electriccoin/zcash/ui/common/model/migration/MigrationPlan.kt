package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.MigrationSchedule
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import java.util.UUID

@Serializable
data class MigrationPlan(
    val id: String,
    val createdAtEpochSeconds: Long,
    val transfers: List<MigrationTransfer>,
    val mode: MigrationMode = MigrationMode.AUTOMATIC,
) {
    val createdAt: Instant get() = Instant.fromEpochSeconds(createdAtEpochSeconds)
    val nextPending: MigrationTransfer? get() = transfers.firstOrNull { it.status == MigrationTransferStatus.PENDING }
    val isComplete: Boolean get() = transfers.all { it.status == MigrationTransferStatus.SENT }
    val completedCount: Int get() = transfers.count { it.status == MigrationTransferStatus.SENT }
    val totalCount: Int get() = transfers.size
}

/**
 * The single conversion from an SDK [MigrationSchedule] (block-height-denominated) to a persisted,
 * epoch-second-denominated [MigrationPlan]. Both the IMMEDIATE (`MigrationReviewVM`) and AUTOMATIC
 * (`FinalizeMigrationScheduleUseCase`) confirm paths must go through this one function — it used to
 * be reimplemented independently in each, and the two copies had already silently diverged (the
 * IMMEDIATE copy never set `expiryAtEpochSeconds`, defaulting every one of its transfers to the
 * always-expired sentinel) despite both looking correct in isolation. That's exactly the
 * duplication shape that let the anchorHeight/epoch-seconds bug survive one fix in an unfixed
 * sibling copy.
 */
fun MigrationSchedule.toMigrationPlan(mode: MigrationMode): MigrationPlan {
    val now = Clock.System.now().epochSeconds
    return MigrationPlan(
        id = UUID.randomUUID().toString(),
        createdAtEpochSeconds = now,
        transfers = transfers.mapIndexed { i, t ->
            MigrationTransfer(
                index = i,
                amountZatoshi = t.amountZatoshi,
                scheduledAtEpochSeconds = now + estimatedSecondsBetweenHeights(t.anchorHeight, t.nextExecutableAfterHeight),
                status = MigrationTransferStatus.PENDING,
                expiryAtEpochSeconds = now + estimatedSecondsBetweenHeights(t.anchorHeight, t.expiryHeight),
            )
        },
        mode = mode,
    )
}
