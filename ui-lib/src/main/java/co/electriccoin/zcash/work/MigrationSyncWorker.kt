package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        // Read live scheduled heights directly from the SDK (NOT the MigrationPlanRepository
        // cache — spec M5: Lane A must use authoritative SDK state to avoid stale plan data).
        val states = sdk.getMigrationTransferStates()
        if (states == null) {
            // No in-progress migration — stop re-arming Lane A entirely.
            Twig.debug { "MIGRATION_DIAG LaneA: no migration in progress, stopping." }
            return Result.success()
        }

        val est = sdk.estimatedChainTip()
        val nowSec = nowEpochSeconds()
        val nextDueEpoch = nextEstimatedDueEpochSeconds(states, est, nowSec)
        val decision = decideLaneARun(
            nowEpochSeconds = nowSec,
            nextEstimatedDueEpochSeconds = nextDueEpoch,
            privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
            isGateBlocked = sdk.isSyncBlocked().first(),
        )

        if (decision == LaneARunDecision.RUN) {
            synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = LANE_A_SYNC_TIMEOUT)
            val proved = sdk.finalizeReadyTransfers()
            Twig.debug { "MIGRATION_DIAG LaneA: proved=$proved" }

            if (sdk.reconcileInvalidations()) {
                // One or more transfer input notes were spent externally — the plan is now
                // invalid. Notify the user, cancel BOTH lanes (Lane B = MigrationScheduler),
                // and do NOT re-arm Lane A.
                migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                MigrationScheduler(applicationContext).cancel(accountKeyId)
                Twig.debug { "MIGRATION_DIAG LaneA: invalidation detected — cancelling both lanes." }
                return Result.success()
            }

            lastNetworkActivity.stampNow()
        }

        // Re-arm Lane A for the next run.
        val reArmDelay = laneAReArmDelay(
            decision = decision,
            nowEpochSeconds = nowEpochSeconds(),
            nextEstimatedDueEpochSeconds = nextDueEpoch,
            privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
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

/** Returns the current wall-clock time as epoch seconds. Extracted for testability. */
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

// ── Pure functions (tested) ────────────────────────────────────────────────────

internal enum class LaneARunDecision { RUN, SKIP_NEAR_DUE, SKIP_GATE_BLOCKED }

/**
 * Decides whether Lane A should run a full sync+prove cycle this invocation.
 *
 * Priority:
 * 1. [LaneARunDecision.SKIP_GATE_BLOCKED] — isSyncBlocked is true (privacy buffer is active);
 *    running a sync now would correlate the sync burst with the pending broadcast.
 * 2. [LaneARunDecision.SKIP_NEAR_DUE] — the next transfer's estimated due time minus the privacy
 *    buffer has already passed; we are inside the pre-due window and should wait for Lane B to
 *    fire instead of advancing the tip prematurely.
 * 3. [LaneARunDecision.RUN] — no reason to skip; proceed with syncToTip + finalizeReadyTransfers.
 */
internal fun decideLaneARun(
    nowEpochSeconds: Long,
    nextEstimatedDueEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    isGateBlocked: Boolean,
): LaneARunDecision =
    when {
        isGateBlocked -> LaneARunDecision.SKIP_GATE_BLOCKED
        nextEstimatedDueEpochSeconds != null &&
            nowEpochSeconds >= nextEstimatedDueEpochSeconds - privacyBufferSeconds ->
            LaneARunDecision.SKIP_NEAR_DUE
        else -> LaneARunDecision.RUN
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
): Long? {
    if (est < 0L) return null
    return states.transfers
        .filter { !it.isSent }
        .minOfOrNull { transfer ->
            val blocksRemaining = (transfer.scheduledHeight - est).coerceAtLeast(0L)
            nowEpochSeconds + blocksRemaining * SECONDS_PER_BLOCK
        }
}

private const val SECONDS_PER_BLOCK = 75L
