package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import java.util.UUID

/**
 * How many successive migration-engine RUNS the account's current residual balance is estimated
 * to need, for a Keystone account, per `estimate_migration_runs`/`OrchardMigrationSdk
 * .estimateMigrationRunCount()`. The engine caps each run at a fixed number of notes it will
 * migrate (currently 50), so a large enough balance needs several distinct full
 * propose→confirm→sign→execute cycles instead of a single AUTOMATIC pass — this field is how the
 * UI communicates that ("Round X of Y").
 *
 * This is a different concept from Keystone's *within-round* QR/firmware batch-signing limit
 * (see `KeystoneBatchChunking.kt`), which chunks a single round's already-proposed transfers into
 * multiple sign/scan QR exchanges — that mechanism is untouched by this field and invisible to it.
 *
 * Only populated when the estimate is genuinely greater than 1 — a single-round migration (the
 * common case) or a sub-quantum residual balance (estimate of 0) both leave this `null`, exactly
 * as for any non-Keystone account. Always recomputed fresh from the live estimate at the moment
 * it's needed (Review screen entry, or right before `FinalizeMigrationScheduleUseCase` persists
 * the plan) — never a persisted, incrementing campaign counter, which is why `current` is always
 * literally `1` ("this round, from here") rather than tracking progress across rounds.
 */
@Serializable
data class MigrationKeystoneRound(val current: Int, val total: Int)

@Serializable
data class MigrationPlan(
    val id: String,
    val createdAtEpochSeconds: Long,
    val transfers: List<MigrationTransfer>,
    val mode: MigrationMode = MigrationMode.AUTOMATIC,
    val keystoneRound: MigrationKeystoneRound? = null,
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
fun MigrationSchedule.toMigrationPlan(mode: MigrationMode, keystoneRound: MigrationKeystoneRound? = null): MigrationPlan {
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
                id = t.id,
            )
        },
        mode = mode,
        keystoneRound = keystoneRound,
    )
}

/**
 * Overrides [MigrationTransfer.status]/[MigrationTransfer.scheduledAtEpochSeconds] from the SDK's
 * live, persisted [MigrationTransferStates] — the cached [MigrationPlan] otherwise only reflects
 * whatever this app-side cache last wrote through (production `rescheduleOverdueTransfer()` and the
 * debug-only `debugRescheduleTransfers()` both currently forget to update it), so it can silently
 * fall behind the SDK's actual state without this. amountZatoshi/createdAtEpochSeconds never change
 * post-commit, so those keep coming from the cache — only the fields the SDK can independently
 * change are overridden here.
 *
 * Correlates by the transfer's real, stable [MigrationTransfer.id] — NOT by [MigrationTransfer.index].
 * The engine assigns real ids in its own funding-note/crossing order, while [MigrationTransfer.index]
 * is this transfer's position in the broadcast-height-sorted array the app displays as "Transfer N".
 * ZIP 318 deliberately shuffles those two orderings apart, so matching by index would silently attach
 * the wrong transfer's live status/schedule to a displayed position (confirmed live — see
 * [co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressVM]).
 */
fun MigrationPlan.withLiveState(live: MigrationTransferStates?): MigrationPlan {
    if (live == null) return this
    val now = Clock.System.now().epochSeconds
    val byId = live.transfers.associateBy { it.id }
    return copy(
        transfers = transfers.map { t ->
            val liveTransfer = byId[t.id] ?: return@map t
            t.copy(
                status = if (liveTransfer.isSent) MigrationTransferStatus.SENT else MigrationTransferStatus.PENDING,
                scheduledAtEpochSeconds =
                    now + estimatedSecondsBetweenHeights(live.tipHeight, liveTransfer.scheduledHeight),
            )
        }
    )
}
