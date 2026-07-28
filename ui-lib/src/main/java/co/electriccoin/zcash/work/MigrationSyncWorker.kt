package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferStates
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Keep
class MigrationSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val lastNetworkActivity: LastNetworkActivityStorageProvider by inject()
    private val migrationNotifier: MigrationNotifier by inject()

    override suspend fun doWork(): Result {
        val accountKeyId = inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID)
            ?: return Result.success()

        val sdk = getOrchardMigrationSdk(accountKeyId) ?: return Result.success()

        // F3: Lane A must terminate once the migration reaches a terminal state. Unlike Lane B,
        // whose only stop signal is states==null, migrationTransferStates() keeps returning rows
        // for a terminal (Complete / permanently-attention) migration so the Complete screens can
        // still read them for display. So gate on the migration STATE here: if terminal, cancel
        // Lane A's own re-arm (return without scheduling). SyncRequiredBeforeNext is NOT terminal —
        // Lane A's sync is exactly what heals it, so that reason keeps Lane A alive.
        if (shouldLaneAStop(sdk.getMigrationState())) {
            Twig.debug { "MIGRATION_DIAG LaneA: migration terminal — stopping Lane A." }
            MigrationSyncScheduler(applicationContext).cancel(accountKeyId)
            return Result.success()
        }

        // Cache privacy buffer duration to avoid redundant calls.
        val privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds

        // Read live scheduled heights directly from the SDK (NOT the MigrationPlanRepository
        // cache — spec M5: Lane A must use authoritative SDK state to avoid stale plan data).
        val states = sdk.getMigrationTransferStates()
        // Estimated chain tip is read immediately after states to minimize drift. The estimated
        // tip is the right denominator for due-time estimation (based on block interval extrapolation),
        // whereas states.tipHeight is the scanned tip — can be hours stale in backgrounded wallets,
        // which would overestimate remaining time. The two reads are adjacent so scheduledHeight
        // offsets and the tip denominator drift by <1 block.
        val est = sdk.estimatedChainTip()

        if (states == null) {
            // No in-progress migration — stop re-arming Lane A entirely.
            Twig.debug { "MIGRATION_DIAG LaneA: no migration in progress, stopping." }
            return Result.success()
        }

        val nowSec = nowEpochSeconds()
        val secondsPerBlock = sdk.estimatedSecondsPerBlock()
        val nextDueEpoch = nextEstimatedDueEpochSeconds(states, est, nowSec, secondsPerBlock)
        val overdueSec = overdueUnsentSeconds(states, est, secondsPerBlock)
        val decision = decideLaneARun(
            nowEpochSeconds = nowSec,
            nextEstimatedDueEpochSeconds = nextDueEpoch,
            privacyBufferSeconds = privacyBufferSeconds,
            isGateBlocked = sdk.isSyncBlocked().first(),
            overdueUnsentSeconds = overdueSec,
        )

        Twig.debug {
            "MIGRATION_DIAG LaneA: run start account=$accountKeyId decision=$decision " +
                "(estimatedTip=$est, nextEstimatedDueEpoch=$nextDueEpoch, overdueUnsentSeconds=$overdueSec, " +
                "now=$nowSec, secondsPerBlock=$secondsPerBlock)"
        }
        if (decision == LaneARunDecision.RUN || decision == LaneARunDecision.RUN_OVERDUE_UNSENT) {
            val burst = synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = LANE_A_SYNC_TIMEOUT)
            Twig.debug { "MIGRATION_DIAG LaneA: syncToTip result=$burst" }
            val proved = sdk.finalizeReadyTransfers()
            Twig.debug { "MIGRATION_DIAG LaneA: proved=$proved" }

            if (sdk.reconcileInvalidations()) {
                // One or more transfer input notes were spent externally — the plan is now
                // invalid. Notify the user, cancel BOTH lanes (Lane B = MigrationScheduler),
                // and do NOT re-arm Lane A.
                migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                MigrationScheduler(applicationContext).cancel(accountKeyId)
                MigrationSyncScheduler(applicationContext).cancel(accountKeyId)
                Twig.debug { "MIGRATION_DIAG LaneA: invalidation detected — cancelling both lanes." }
                return Result.success()
            }

            lastNetworkActivity.stampNow()
        }

        // Re-arm Lane A for the next run — plan-driven for the test phase: target the next
        // unsent transfer's estimated due time minus a lead (privacy buffer + sync headroom), so
        // the sync+prove pass lands BEFORE the window instead of on a blind cadence tick. The
        // post-run states are re-read so a transfer just sent above doesn't anchor the target.
        //
        // TEST-PHASE DESIGN NOTE: the final Android implementation per Kris uses a fixed sync
        // cadence (60 min mainnet); the engine's own `sync_wakeup_schedule` (librustzcash #2801,
        // boundary + settle margin + jitter) is the precise source of these wake-ups and will be
        // consumed via FFI once the SDK moves to the rc.3+ engine crates (planned with the
        // feature/ironwood-slipstream merge — the [patch.crates-io] path-dep is currently
        // inactive against the locked rc.1 engine, which predates #2801). The cadence below
        // remains the fallback either way.
        val postRunStates = sdk.getMigrationTransferStates()
        val postRunEst = sdk.estimatedChainTip()
        val reArmDelay = laneAPlanDrivenReArmDelay(
            decision = decision,
            nowEpochSeconds = nowEpochSeconds(),
            nextEstimatedDueEpochSeconds =
                if (postRunStates != null) {
                    nextEstimatedDueEpochSeconds(postRunStates, postRunEst, nowEpochSeconds(), secondsPerBlock)
                } else {
                    nextDueEpoch
                },
            privacyBufferSeconds = privacyBufferSeconds,
            cadenceSeconds = laneACadence().inWholeSeconds,
            jitterSeconds = LANE_A_JITTER.inWholeSeconds,
            random = Random,
        )
        MigrationSyncScheduler(applicationContext).schedule(accountKeyId, reArmDelay)
        Twig.debug { "MIGRATION_DIAG LaneA: decision=$decision, re-arming in $reArmDelay" }

        return Result.success()
    }
}

internal val LANE_A_SYNC_TIMEOUT = 3.minutes
internal val LANE_A_JITTER = 10.minutes

/**
 * Lane A cadence: 5 min on testnet, 60 min on mainnet.
 *
 * Uses [BuildConfig.FLAVOR] because the SDK's network id is not cheaply reachable from a static
 * context without a full OrchardMigrationSdk instance. BuildConfig.FLAVOR contains the full
 * combined product flavor string (e.g. "zcashtestnetFoss") so a substring match is reliable.
 */
internal fun laneACadence(): Duration =
    if (BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)) 5.minutes else 60.minutes

/**
 * How far BEFORE the first broadcast window Lane A's first run is aimed (see
 * FinalizeMigrationScheduleUseCase): privacy buffer (3 min testnet / 10 min mainnet) plus sync
 * headroom, so the first sync+prove pass completes and the quiet gap still elapses before Lane B
 * fires. Static per-flavor equivalent of the worker's own
 * `privacyBufferSeconds + LANE_A_SYNC_LEAD_HEADROOM_SECONDS` (the SDK isn't in reach at
 * scheduling time without an extra round trip).
 */
internal fun laneAFirstRunLead(): Duration =
    if (BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)) 5.minutes else 30.minutes

/** Returns the current wall-clock time as epoch seconds. Extracted for testability. */
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

// ── Pure functions (tested) ────────────────────────────────────────────────────

/**
 * F3: whether Lane A should stop re-arming for the given migration [state].
 *
 * Terminal states — the plan can make no further automatic progress, so Lane A's sync+prove loop
 * has nothing left to do:
 * - [MigrationState.Complete] — all transfers confirmed on-chain.
 * - [MigrationState.RequiresAttention] with [AttentionReason.InvalidTransfer] or
 *   [AttentionReason.TransferExpired] — the plan is dead; the app-open router handles it.
 *
 * NON-terminal (Lane A keeps running):
 * - [AttentionReason.SyncRequiredBeforeNext] — Lane A's own sync is exactly what heals this, so
 *   stopping here would strand the migration.
 * - [MigrationState.InProgress] / pre-commit states — the migration is still executing.
 */
internal fun shouldLaneAStop(state: MigrationState): Boolean =
    when (state) {
        is MigrationState.Complete -> true
        is MigrationState.RequiresAttention ->
            when (state.reason) {
                is AttentionReason.InvalidTransfer, is AttentionReason.TransferExpired -> true
                is AttentionReason.SyncRequiredBeforeNext -> false
            }
        else -> false
    }

internal enum class LaneARunDecision { RUN, RUN_OVERDUE_UNSENT, SKIP_NEAR_DUE, SKIP_GATE_BLOCKED }

/**
 * Decides whether Lane A should run a full sync+prove cycle this invocation.
 *
 * Priority:
 * 1. [LaneARunDecision.SKIP_GATE_BLOCKED] — isSyncBlocked is true (privacy buffer is active);
 *    running a sync now would correlate the sync burst with the pending broadcast.
 * 2. [LaneARunDecision.RUN_OVERDUE_UNSENT] — the earliest pending transfer's window passed at
 *    least [LANE_A_OVERDUE_OVERRIDE_SECONDS] ago and it is STILL unsent. A proved+due transfer is
 *    broadcast by Lane B within seconds of its window, so a transfer this far past due means Lane
 *    B is returning AwaitingProof — and the step-aside below would deadlock the plan: Lane A
 *    forever yields to a broadcast that cannot happen without Lane A's own sync+prove (observed
 *    live: Lane B shifting the same transfer every 5 s while Lane A logged SKIP_NEAR_DUE every
 *    3 min, scanned tip frozen). Syncing now is safe for the privacy choreography: Lane B's
 *    preflight re-imposes the quiet gap from the fresh sync stamp before it broadcasts.
 * 3. [LaneARunDecision.SKIP_NEAR_DUE] — the next transfer's estimated due time minus the privacy
 *    buffer has already passed; we are inside the pre-due window and should wait for Lane B to
 *    fire instead of advancing the tip prematurely.
 * 4. [LaneARunDecision.RUN] — no reason to skip; proceed with syncToTip + finalizeReadyTransfers.
 */
internal fun decideLaneARun(
    nowEpochSeconds: Long,
    nextEstimatedDueEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    isGateBlocked: Boolean,
    overdueUnsentSeconds: Long? = null,
): LaneARunDecision =
    when {
        isGateBlocked -> LaneARunDecision.SKIP_GATE_BLOCKED
        overdueUnsentSeconds != null && overdueUnsentSeconds >= LANE_A_OVERDUE_OVERRIDE_SECONDS ->
            LaneARunDecision.RUN_OVERDUE_UNSENT
        nextEstimatedDueEpochSeconds != null &&
            nowEpochSeconds >= nextEstimatedDueEpochSeconds - privacyBufferSeconds ->
            LaneARunDecision.SKIP_NEAR_DUE
        else -> LaneARunDecision.RUN
    }

internal const val LANE_A_OVERDUE_OVERRIDE_SECONDS = 60L

/**
 * How many estimated seconds the MOST overdue pending transfer is past its window — `null` when
 * nothing is pending, nothing is past due, or the estimated tip is unavailable (`est < 0`).
 * Uses the estimated tip deliberately: accelerating `scheduled_height` due-ness is exactly what
 * the estimate is for (hard invariant 1); expiry/invalidity decisions elsewhere stay on the
 * scanned tip.
 */
internal fun overdueUnsentSeconds(
    states: MigrationTransferStates,
    est: Long,
    secondsPerBlock: Long,
): Long? {
    if (est < 0L) return null
    return states.transfers
        .filter { !it.isSent }
        .maxOfOrNull { (est - it.scheduledHeight) * secondsPerBlock }
        ?.takeIf { it > 0L }
}

/**
 * Computes the delay until the next Lane A re-arm.
 *
 * - [LaneARunDecision.SKIP_NEAR_DUE]: wait until after the due window closes
 *   (`nextDue + buffer − now`), with a minimum of 60 s to prevent hot-loop spinning when the
 *   estimate is already in the past (spec M5 hot-loop guard).
 * - Otherwise: cadence ± random jitter in `[-jitter, +jitter]`.
 */
internal fun laneAReArmDelay(
    decision: LaneARunDecision,
    nowEpochSeconds: Long,
    nextEstimatedDueEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    cadenceSeconds: Long,
    jitterSeconds: Long,
    random: Random,
): Duration {
    if (decision == LaneARunDecision.SKIP_NEAR_DUE && nextEstimatedDueEpochSeconds != null) {
        val remaining = nextEstimatedDueEpochSeconds + privacyBufferSeconds - nowEpochSeconds
        return maxOf(remaining, MIN_LANE_A_BACKOFF_SECONDS).seconds
    }
    val jitterOffset = random.nextLong(-jitterSeconds, jitterSeconds + 1)
    return (cadenceSeconds + jitterOffset).coerceAtLeast(MIN_LANE_A_BACKOFF_SECONDS).seconds
}

/**
 * Plan-driven Lane A re-arm (test phase — see the design note at the call site): aim the next run
 * at `nextDue − lead`, where the lead is the privacy buffer plus [LANE_A_SYNC_LEAD_HEADROOM_SECONDS]
 * of sync headroom, so the transfer is proved before its window opens and Lane B's quiet-gap check
 * still has room after the sync. Clamped to `[MIN_LANE_A_BACKOFF_SECONDS, cadence]`:
 * - already inside the lead (or past due) → the floor, 60 s — the overdue override
 *   ([LaneARunDecision.RUN_OVERDUE_UNSENT]) takes over if a proof is actually missing;
 * - no pending transfer / no estimate → plain cadence via [laneAReArmDelay];
 * - the SKIP_NEAR_DUE wait-out-the-window rule is unchanged (delegated).
 */
internal fun laneAPlanDrivenReArmDelay(
    decision: LaneARunDecision,
    nowEpochSeconds: Long,
    nextEstimatedDueEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    cadenceSeconds: Long,
    jitterSeconds: Long,
    random: Random,
): Duration {
    if (decision == LaneARunDecision.SKIP_NEAR_DUE || nextEstimatedDueEpochSeconds == null) {
        return laneAReArmDelay(
            decision = decision,
            nowEpochSeconds = nowEpochSeconds,
            nextEstimatedDueEpochSeconds = nextEstimatedDueEpochSeconds,
            privacyBufferSeconds = privacyBufferSeconds,
            cadenceSeconds = cadenceSeconds,
            jitterSeconds = jitterSeconds,
            random = random,
        )
    }
    val lead = privacyBufferSeconds + LANE_A_SYNC_LEAD_HEADROOM_SECONDS
    val target = nextEstimatedDueEpochSeconds - lead - nowEpochSeconds
    return target.coerceIn(MIN_LANE_A_BACKOFF_SECONDS, cadenceSeconds).seconds
}

internal const val LANE_A_SYNC_LEAD_HEADROOM_SECONDS = 120L

private const val MIN_LANE_A_BACKOFF_SECONDS = 60L

/**
 * Returns the minimum estimated epoch-second at which the earliest PENDING (not yet sent)
 * transfer will be ready to broadcast, based on `(scheduledHeight − est) * 75 s/block`.
 *
 * Returns `null` when:
 * - [states] has no pending transfers (all sent or empty list)
 * - [est] is the sentinel value `-1` (chain tip unavailable)
 *
 * The block-time constant 75 s matches [cash.z.ecc.android.sdk.ext.ZcashSdk.BLOCK_INTERVAL_MILLIS]
 * / 1000. Blocks remaining is clamped to ≥ 0 so an already-past height gives an offset of 0
 * instead of a negative result.
 *
 * NOTE: This function considers only the account whose [states] are passed. In a multi-account
 * wallet each account would have its own Lane A worker carrying its own accountKeyId; the
 * single-account path (most deployments) is the designed norm and is fully correct here.
 */
internal fun nextEstimatedDueEpochSeconds(
    states: MigrationTransferStates,
    est: Long,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    secondsPerBlock: Long = SECONDS_PER_BLOCK,
): Long? {
    if (est < 0L) return null
    return states.transfers
        .filter { !it.isSent }
        .minOfOrNull { transfer ->
            val blocksRemaining = (transfer.scheduledHeight - est).coerceAtLeast(0L)
            nowEpochSeconds + blocksRemaining * secondsPerBlock
        }
}

private const val SECONDS_PER_BLOCK = 75L
