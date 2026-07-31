package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.MigrationAdvanceStep
import cash.z.ecc.android.sdk.MigrationBlocker
import cash.z.ecc.android.sdk.MigrationSyncWakeup
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.migration.BuildConfig
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.toSnapshot
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.MigrationSdkLookup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The single migration execution worker — the app-side half of the engine-driven loop
 * (spec: 2026-07-30-engine-state-machine-adoption-design.md).
 *
 * The `zcash_pool_migration` state machine decides WHAT to do and WHEN
 * ([OrchardMigrationSdk.nextStep] + [OrchardMigrationSdk.syncWakeupSchedule]); this worker supplies
 * only what the engine cannot know:
 *  - privacy timing — the quiet gap before a crossing broadcast, sync-XOR-broadcast per worker
 *    execution (ZIP 318 de-correlation), crossing send pacing, and the preparation fast-track
 *    exception (in-pool note splits leak nothing, so they go back-to-back with no gaps);
 *  - OS plumbing — WorkManager re-arming, height→wall-clock projection, the kill switch;
 *  - I/O — sync bursts, (Tor) broadcasts, retries and timeouts;
 *  - user surfacing — notifications; the home banner reads the same engine statuses reactively.
 *
 * Exactly ONE engine action happens per execution: a run either syncs (proves) or broadcasts,
 * never both. The loop is: run → ask `nextStep` ("what?") → do it → ask `syncWakeupSchedule` +
 * statuses ("when?") → re-arm ONE future run → repeat.
 */
@Keep
class MigrationWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters),
    KoinComponent {
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider by inject()
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider by inject()
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val lastNetworkActivity: LastNetworkActivityStorageProvider by inject()

    override suspend fun doWork(): Result {
        val accountKeyId =
            inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID)
                ?: getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId().also {
                    migrationLog("Worker: no accountKeyId in inputData — falling back to selected account $it (pre-upgrade job)")
                }

        // Dead-man's switch heartbeat: the run STARTED — the fallback alarm's late check passes,
        // and any step-due fallback notification is now obsolete.
        MigrationWorkerHeartbeat.stampRun(applicationContext, accountKeyId)
        migrationNotifier.cancelStepDue(accountKeyId)

        val sdk =
            when (val lookup = getOrchardMigrationSdk.lookup(accountKeyId)) {
                is MigrationSdkLookup.Ready -> {
                    lookup.sdk
                }

                MigrationSdkLookup.NotReady -> {
                    // A not-yet-initialized wallet right after an app update/reboot must not
                    // silently consume (and thereby kill) the self-rechaining loop — retry until
                    // the SDK is reachable.
                    migrationLog("Worker: SDK not ready — retrying via WorkManager backoff.")
                    return Result.retry()
                }

                MigrationSdkLookup.Gone -> {
                    // Kill switch: the wallet was deleted or this (Keystone) account disconnected.
                    // Retrying would zombie-loop forever for an owner that no longer exists.
                    migrationLog("Worker: account/wallet gone — cancelling the migration work chain.")
                    MigrationScheduler(applicationContext).cancel(accountKeyId)
                    migrationNotifier.cancel(accountKeyId)
                    return Result.success()
                }
            }

        val step = sdk.nextStep()
        if (step == null) {
            migrationLog("Worker: no migration in progress — stopping the work chain.")
            return Result.success()
        }
        migrationLog("Worker: run start account=$accountKeyId step=$step")
        return when (step) {
            MigrationAdvanceStep.Complete -> completeRun(accountKeyId)
            is MigrationAdvanceStep.Rebuild -> rebuildRun(sdk, accountKeyId, step.transferId)
            is MigrationAdvanceStep.Prove -> syncRun(sdk, accountKeyId)
            is MigrationAdvanceStep.Broadcast -> broadcastRun(sdk, accountKeyId)
            MigrationAdvanceStep.Waiting -> waitingRun(sdk, accountKeyId)
        }
    }

    /**
     * The engine answers `nextStep` at the SCANNED tip, which can lag wall clock by hours in a
     * backgrounded wallet — nextStep is now Broadcast-authoritative (it checks
     * `next_broadcastable` at the estimated tip internally), so a due broadcast is dispatched
     * straight to [broadcastRun] by [doWork] and never reaches this function. `waitingRun` only
     * handles a genuine engine `Waiting`: sweep to completion, surface an unprovable blocker, or
     * re-arm.
     */
    private suspend fun waitingRun(sdk: OrchardMigrationSdk, accountKeyId: String): Result {
        val states = sdk.getMigrationTransferStates()
        val allSent = states != null && states.transfers.isNotEmpty() && states.transfers.all { it.isSent }
        val hasUnprovableBlocker = states?.transfers?.any { it.blocker == MigrationBlocker.UNPROVABLE_ANCHOR } == true
        return when (waitingDisposition(allSent, hasUnprovableBlocker)) {
            WaitingDisposition.COMPLETION_SWEEP -> {
                // Everything broadcast, awaiting mining. Mining is only observed by a scan-driven
                // reconcile, so the sweep must be a REAL sync run — a passive wait would never let
                // the engine reach Complete in the background (review M2).
                migrationLog("Worker: all transactions sent — completion sweep sync run.")
                syncRun(sdk, accountKeyId)
            }

            WaitingDisposition.SURFACE_UNPROVABLE -> {
                surfaceUnprovableBlocker(sdk, accountKeyId, states)
                reArm(sdk, accountKeyId)
                Result.success()
            }

            WaitingDisposition.RE_ARM -> {
                reArm(sdk, accountKeyId)
                Result.success()
            }
        }
    }

    /**
     * A sync (prove) run: syncToTip + finalizeReadyTransfers + reconcile, gated by the
     * post-broadcast privacy buffer. Nothing broadcasts in this run — sync XOR broadcast per
     * execution. Afterwards the engine is asked again: a ready preparation chains immediately
     * (fast-track), a crossing waits out the quiet gap this sync just opened.
     */
    private suspend fun syncRun(sdk: OrchardMigrationSdk, accountKeyId: String): Result {
        if (sdk.isSyncBlocked().first()) {
            migrationLog("Worker: sync run blocked by the post-broadcast privacy gate — deferring.")
            reArm(sdk, accountKeyId, floor = sdk.privacySyncBufferDuration())
            return Result.success()
        }
        val burst = synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = SYNC_TIMEOUT)
        migrationLog("Worker: syncToTip result=$burst")
        val proved = sdk.finalizeReadyTransfers()
        migrationLog("Worker: proved=$proved")
        if (sdk.reconcileInvalidations()) {
            // The plan is invalid (input notes spent externally) — notify and do NOT re-arm; the
            // app-open router (CheckMigrationRecoveryUseCase) takes over from here.
            migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
            MigrationScheduler(applicationContext).cancel(accountKeyId)
            migrationLog("Worker: reconcile found an invalidation — stopping the work chain.")
            return Result.success()
        }
        lastNetworkActivity.stampNow()

        return when (val next = sdk.nextStep()) {
            is MigrationAdvanceStep.Broadcast -> {
                val prep = nextDueUnsentIsPreparation(sdk.getMigrationTransferStates(), sdk.estimatedChainTip())
                val chainDelay = if (prep) PREP_FAST_TRACK_REARM else sdk.privacySyncBufferDuration()
                MigrationScheduler(applicationContext).schedule(accountKeyId, chainDelay)
                migrationLog("Worker: sync done, next=$next — broadcast run in $chainDelay")
                Result.success()
            }

            MigrationAdvanceStep.Complete -> {
                completeRun(accountKeyId)
            }

            is MigrationAdvanceStep.Rebuild -> {
                rebuildRun(sdk, accountKeyId, next.transferId)
            }

            else -> {
                // Prove again (boundary not yet settled at the new tip) or Waiting.
                migrationLog("Worker: sync done, next=$next — re-arming.")
                surfaceUnprovableBlocker(sdk, accountKeyId, sdk.getMigrationTransferStates())
                reArm(sdk, accountKeyId)
                Result.success()
            }
        }
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun broadcastRun(sdk: OrchardMigrationSdk, accountKeyId: String): Result {
        val states = sdk.getMigrationTransferStates()
        val est = sdk.estimatedChainTip()
        // Capture the transaction the ENGINE will actually serve (vec/id order among proved+due —
        // review L2), falling back to schedule order when nothing is broadcastable yet, so the
        // fast-track preflight AND the post-send notification attribute the right kind.
        val nextCandidate = engineBroadcastCandidate(states, est) ?: earliestUnsent(states)
        val prepFastTrack =
            nextCandidate != null &&
                !nextCandidate.isTransfer &&
                nextCandidate.isProved &&
                nextCandidate.scheduledHeight <= est

        // status is a Flow<Status> — timeout if cold; null synchronizer is non-syncing.
        // timeout → assume SYNCING → defer (privacy-safe default; production status is a StateFlow
        // and answers immediately).
        val syncing =
            synchronizerProvider.synchronizer.value?.let { synchronizer ->
                withTimeoutOrNull(STATUS_READ_TIMEOUT) { synchronizer.status.first() } ?: Synchronizer.Status.SYNCING
            } == Synchronizer.Status.SYNCING
        val lastActivity = lastNetworkActivity.get()
        val preflight =
            decideBroadcastPreflight(
                synchronizerSyncing = syncing,
                nowEpochSeconds = nowEpochSeconds(),
                lastNetworkActivityEpochSeconds = lastActivity?.epochSecond,
                privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
                prepFastTrack = prepFastTrack,
            )
        migrationLog(
            "Worker: broadcast preflight=$preflight " +
                "(syncing=$syncing, prepFastTrack=$prepFastTrack, lastNetworkActivity=$lastActivity)"
        )
        if (preflight == BroadcastPreflight.DEFER) {
            // Local delay: engine untouched. A fast-tracked preparation only ever defers on a live
            // sync overlap — re-arm short instead of a full privacy buffer.
            val deferDelay = if (prepFastTrack) PREP_FAST_TRACK_REARM else sdk.privacySyncBufferDuration()
            MigrationScheduler(applicationContext).schedule(accountKeyId, deferDelay)
            migrationLog("Worker: deferring broadcast $deferDelay — a sync source is live or the quiet gap is unmet.")
            return Result.success()
        }

        val snapshotBefore = sdk.snapshot()
        val useTor = isMigrationTorEnabledStorageProvider.get(accountKeyId)

        // Hard timeout around the whole broadcast attempt: a cold-bootstrapping Tor client can
        // hang the submit indefinitely (observed live: tx stuck in-flight 10+ minutes until the
        // WorkManager execution ceiling killed the worker and nothing re-armed). On timeout the
        // native call may still complete detached — a re-submit of the same tx is safely
        // classified as a duplicate by the SDK (F2 classifier + mined-height probe), so
        // re-arming for another attempt is correct.
        val outcome =
            withTimeoutOrNull(BROADCAST_ATTEMPT_TIMEOUT) {
                executeWithRetries {
                    sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true)
                }
            } ?: run {
                migrationLog("Worker: broadcast attempt timed out after $BROADCAST_ATTEMPT_TIMEOUT — re-arming.")
                reArm(sdk, accountKeyId, floor = REARM_FLOOR)
                return Result.success()
            }
        return when (outcome) {
            is TransferAttemptOutcome.NothingDue -> {
                // The estimate raced ahead of the engine's own due check — re-arm normally.
                reArm(sdk, accountKeyId)
                migrationLog("Worker: NothingDue — re-armed for the next window.")
                Result.success()
            }

            is TransferAttemptOutcome.AwaitingProof -> {
                // Defensive: nextStep said Broadcast, so the engine had a proved transaction — a
                // proof can only have vanished through a concurrent reorg/rescan. Re-arm floored;
                // the next run re-asks the engine from scratch.
                migrationLog("Worker: AwaitingProof for ${outcome.transferId} despite a Broadcast step — re-arming.")
                reArm(sdk, accountKeyId, floor = REARM_FLOOR)
                Result.success()
            }

            is TransferAttemptOutcome.Executed -> {
                handleExecuted(sdk, accountKeyId, outcome.result, snapshotBefore, sentWasPrep = nextCandidate?.isTransfer == false)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleExecuted(
        sdk: OrchardMigrationSdk,
        accountKeyId: String,
        result: TransferResult,
        snapshotBefore: LiveMigrationSnapshot?,
        sentWasPrep: Boolean,
    ): Result =
        when (result) {
            is TransferResult.Success -> {
                migrationLog("Worker: sent — txId=${result.txId}")
                // Everything below reads the engine's post-send state live — there is no cache to
                // write through anymore (the banner reads the same live states).
                val snapshot = sdk.snapshot()
                if (sentWasPrep) {
                    // "Transfer 0 of 11 complete" after a note split confused users (the crossing
                    // count ignores splits) — splits announce their own progress.
                    migrationNotifier.notifyNoteSplitProgress(
                        accountKeyId,
                        completedSplits = snapshot?.preparations?.count { it.isSent } ?: 0,
                        totalSplits = snapshot?.preparations?.size ?: 0,
                    )
                }
                // Single post-send engine read for every decision below (review L5).
                val postStates = sdk.getMigrationTransferStates()
                val anyUnsent = postStates?.transfers?.any { !it.isSent } == true
                if (anyUnsent) {
                    // Prep fast-track: whole ready prep batches go back-to-back — no send spacing
                    // between preparations (one logical tree, in-pool, nothing to de-correlate).
                    // CROSSINGS take the opposite rule: never two sends closer than the privacy
                    // buffer, even in catch-up (a starved worker once fired 5 overdue crossings in
                    // ~51 s — grid spacing means nothing if catch-up collapses it into one
                    // network-timing cluster). The non-fast-track case delegates to reArm — it
                    // targets the EARLIEST relevant moment across engine wake-ups and ALL unsent
                    // heights INCLUDING preparations; an ad-hoc crossing-only delay here used to
                    // sleep past inter-layer prep windows and compress the serial prep tail toward
                    // the crossings' anchor boundaries — the exact tx9 latency condition (review H1).
                    if (nextDueUnsentIsPreparation(postStates, sdk.estimatedChainTip())) {
                        MigrationScheduler(applicationContext).schedule(accountKeyId, PREP_FAST_TRACK_REARM)
                        migrationLog("Worker: ready preparation next — chaining in $PREP_FAST_TRACK_REARM")
                    } else {
                        reArm(sdk, accountKeyId, floor = sdk.privacySyncBufferDuration())
                    }
                    if (!sentWasPrep && snapshot != null) {
                        migrationNotifier.notifyTransferComplete(accountKeyId, snapshot.completedCount, snapshot.totalCount)
                    }
                } else {
                    migrationNotifier.notifyMigrationComplete(accountKeyId)
                    // Everything sent — keep observing until the engine reports Complete (the
                    // completeRun stop). The next wake lands in waitingRun's completion-sweep
                    // branch, which runs a REAL sync so mining is actually observed (review M2).
                    reArm(sdk, accountKeyId, floor = sdk.privacySyncBufferDuration())
                    migrationLog("Worker: all transfers sent — completion sweep armed.")
                }
                Result.success()
            }

            is TransferResult.NetworkError -> {
                // Retries already exhausted (or the failure was non-retryable) inside
                // executeWithRetries — settle into an error state now rather than asking
                // WorkManager for yet another attempt.
                migrationLog("Worker: network error after retries, isTorFailure=${result.isTorFailure}")
                if (result.isTorFailure) {
                    // Persist a flag so app-open reconciliation (CheckMigrationRecoveryUseCase)
                    // routes back through the Sending screen instead of the generic
                    // manual-confirmation path, and surface a distinct notification.
                    pendingMigrationTorFailureStorageProvider.store(accountKeyId, true)
                    migrationNotifier.notifyMigrationTorFailure(accountKeyId)
                } else if (snapshotBefore?.nextPending != null) {
                    // Nothing else re-arms a future attempt for a non-retryable failure — the
                    // user must open the app and act, same as a missed/stalled window.
                    migrationNotifier.notifyManualConfirmationRequired(
                        accountKeyId,
                        snapshotBefore.nextPending!!.index + 1,
                        snapshotBefore.totalCount,
                    )
                }
                Result.failure()
            }

            TransferResult.InvalidNote -> {
                // State is now RequiresAttention(InvalidTransfer) — notes were spent outside the
                // migration flow. On-launch reconciliation surfaces the prompt, but the user still
                // needs telling since nothing else runs meanwhile. No re-arm — terminal until the
                // user acts.
                migrationLog("Worker: transfer invalid (note spent externally) — user action required on next open.")
                migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                Result.success()
            }

            TransferResult.Expired -> {
                // State is now RequiresAttention(TransferExpired) — the anchor expired before the
                // broadcast could happen. Distinct copy from InvalidNote, same terminal handling.
                migrationLog("Worker: transfer expired — user action required on next open.")
                migrationNotifier.notifyTransferExpired(accountKeyId)
                Result.success()
            }
        }

    /**
     * The engine wants [transferId] rebuilt (expired today; unprovable-anchor too once the engine
     * change request ships). A rebuild needs a fresh signature, so it is user-driven: surface the
     * attention notification and stop re-arming — the home banner and the app-open router route
     * the user into the invalid/reschedule screen, and recovery re-arms the chain afterwards.
     */
    private suspend fun rebuildRun(sdk: OrchardMigrationSdk, accountKeyId: String, transferId: Long): Result {
        val snapshot = sdk.snapshot()
        migrationLog("Worker: engine requests Rebuild{$transferId} — user-driven reschedule required.")
        migrationNotifier.notifyRescheduleRequired(
            accountKeyId,
            (snapshot?.nextPending?.index?.plus(1)) ?: 1,
            snapshot?.totalCount ?: 0,
        )
        return Result.success()
    }

    /** All transactions mined — nothing left to fold anywhere; just stop the chain. */
    private suspend fun completeRun(accountKeyId: String): Result {
        migrationLog("Worker: migration complete — stopping the work chain. (account=$accountKeyId)")
        return Result.success()
    }

    /**
     * TODO(remove: engine UnprovableAnchor): the SDK synthesizes
     * [MigrationBlocker.UNPROVABLE_ANCHOR] from the backend's late-dependency guard until the
     * engine change request ships — the engine will then emit `Rebuild` for it and this surfacing
     * collapses into [rebuildRun]. Until then, notify here so the user learns the plan needs a
     * reschedule without waiting for an app open (the home banner shows the same attention state).
     */
    private suspend fun surfaceUnprovableBlocker(sdk: OrchardMigrationSdk, accountKeyId: String, states: MigrationTransferStates?) {
        val stuck = states?.transfers?.firstOrNull { it.blocker == MigrationBlocker.UNPROVABLE_ANCHOR } ?: return
        val snapshot = sdk.snapshot()
        migrationLog("Worker: transfer ${stuck.id} blocked on an unprovable anchor — user-driven reschedule required.")
        migrationNotifier.notifyRescheduleRequired(
            accountKeyId,
            (snapshot?.nextPending?.index?.plus(1)) ?: 1,
            snapshot?.totalCount ?: 0,
        )
    }

    /**
     * The "when?" half of the loop: one future run at the earliest relevant moment — the engine's
     * next sync wake-up ([OrchardMigrationSdk.syncWakeupSchedule]) or the next unsent
     * transaction's scheduled height, whichever comes first, projected height→wall-clock at the
     * measured block rate. Falls back to a flat cadence when neither is available.
     */
    private suspend fun reArm(sdk: OrchardMigrationSdk, accountKeyId: String, floor: Duration = Duration.ZERO) {
        val states = sdk.getMigrationTransferStates()
        val wakeups = sdk.syncWakeupSchedule()
        val est = sdk.estimatedChainTip()
        val delay =
            nextWake(
                states,
                wakeups,
                est,
                sdk.estimatedSecondsPerBlock(),
                lastActivityEpochSeconds = lastNetworkActivity.get()?.epochSecond,
                privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
                nowEpochSeconds = nowEpochSeconds(),
            )
        MigrationScheduler(applicationContext).schedule(accountKeyId, maxOf(delay ?: migrationCadence(), floor))
        // The full "why" of the chosen wake, so timing is diagnosable from logs alone: every
        // engine wake-up height, the next unsent due height, the tip estimate, and the floor.
        migrationLog(
            "Worker: re-armed in ${maxOf(delay ?: migrationCadence(), floor)} " +
                "(engineWakeups=${wakeups?.map { "${it.height}->${it.covers}" }}, " +
                "nextDue=${states?.transfers?.filter { !it.isSent }?.minOfOrNull { it.scheduledHeight }}, " +
                "estimatedTip=$est, floor=$floor" +
                if (delay == null) ", cadence fallback)" else ")"
        )
    }
}

/** Live snapshot of this SDK's engine states — the worker's plan view (never cached). */
private suspend fun OrchardMigrationSdk.snapshot(): LiveMigrationSnapshot? =
    getMigrationTransferStates()?.let {
        val est = estimatedChainTip()
        it.toSnapshot(
            estimatedTip = if (est >= 0) est else it.tipHeight,
            secondsPerBlock = estimatedSecondsPerBlock(),
            nowEpochSeconds = nowEpochSeconds(),
        )
    }

private val STATUS_READ_TIMEOUT = 2.seconds
internal val SYNC_TIMEOUT = 3.minutes
internal val REARM_FLOOR = 60.seconds
private val BROADCAST_ATTEMPT_TIMEOUT = 3.minutes

/**
 * Re-arm delay for the preparation fast-track: back-to-back scheduling for ready prep batches
 * (WorkManager dispatch latency is the only real gap) and the short retry when a fast-tracked
 * prep only lost its window to a live sync overlap.
 */
internal val PREP_FAST_TRACK_REARM = 1.seconds

internal const val MIN_REARM_SECONDS = 60L

/** Returns the current wall-clock time as epoch seconds. Extracted for testability. */
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

/**
 * Fallback-only cadence: 5 min on testnet, 60 min on mainnet. Used ONLY when neither the engine's
 * wake-up schedule nor live transfer states (or the tip estimate) are available — every regular
 * wake is computed from the engine's own schedule instead (see [nextWake]).
 *
 * Uses [BuildConfig.FLAVOR] because the SDK's network id is not cheaply reachable from a static
 * context without a full OrchardMigrationSdk instance.
 */
internal fun migrationCadence(): Duration =
    if (BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)) 5.minutes else 60.minutes

// Same attempt count (3) as MigrationSendingVM.sendOnce()'s foreground retry loop — but not the
// same retry trigger: sendOnce() retries while polling for readiness (result == null) and stops
// on any non-null result; this retries only on a retryable NetworkError and stops on null. Each
// loop is correct for its own context (foreground polls for the transfer becoming ready;
// background rides out a flaky network) — they just happen to share the same attempt budget.
private const val MAX_BROADCAST_ATTEMPTS = 3
private const val BROADCAST_RETRY_DELAY_MS = 1500L

/**
 * Calls [attempt] up to [maxAttempts] times, retrying only while the result is an
 * [TransferAttemptOutcome.Executed] wrapping a retryable [TransferResult.NetworkError] — anything
 * else (NothingDue, AwaitingProof, a non-retryable error, success) short-circuits immediately.
 * Returns null only when [attempt] itself returns null (should not happen with the current SDK
 * contract, but guards against future changes). Top-level and `internal` (rather than a private
 * method on [MigrationWorker]) specifically so it's unit-testable without Koin or WorkManager,
 * neither of which this codebase has test infrastructure for today.
 */
internal suspend fun executeWithRetries(
    maxAttempts: Int = MAX_BROADCAST_ATTEMPTS,
    retryDelayMs: Long = BROADCAST_RETRY_DELAY_MS,
    attempt: suspend () -> TransferAttemptOutcome,
): TransferAttemptOutcome? {
    var result: TransferAttemptOutcome? = null
    for (i in 0 until maxAttempts) {
        if (i > 0) delay(retryDelayMs)
        result = attempt()
        val current = result
        val shouldRetry =
            current is TransferAttemptOutcome.Executed &&
                current.result is TransferResult.NetworkError &&
                (current.result as TransferResult.NetworkError).retryable
        if (!shouldRetry) break
    }
    return result
}

/**
 * What a broadcast run should do before calling the SDK's executeNextPendingTransfer.
 *
 * - [BroadcastPreflight.DEFER] — the foreground synchronizer is actively syncing, OR the privacy
 *   quiet gap since the last network activity has not yet elapsed. Engine untouched.
 * - [BroadcastPreflight.BROADCAST] — all sources are quiet and the gap has elapsed.
 */
internal enum class BroadcastPreflight { BROADCAST, DEFER }

/**
 * Pure preflight decision for a broadcast run — takes pre-computed scalars so it is unit-testable
 * without Koin, WorkManager or a real SDK.
 *
 * [lastNetworkActivityEpochSeconds] is null when no activity has ever been stamped (first run);
 * in that case the gap check is skipped and BROADCAST is returned.
 */
internal fun decideBroadcastPreflight(
    synchronizerSyncing: Boolean,
    nowEpochSeconds: Long,
    lastNetworkActivityEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    prepFastTrack: Boolean = false,
): BroadcastPreflight {
    // Preparation fast-track (security split, 2026-07-30): note-split preparations are fully
    // shielded IN-POOL transactions — amounts and spend links hidden, natural recent anchor with
    // the same anonymity set as all ordinary Orchard traffic. The sync/broadcast de-correlation
    // ceremony exists for CROSSINGS (public amount + tiny migration-anchor anonymity set), and
    // during the prep phase no crossing exists to correlate against. So when the next due pending
    // transaction is a preparation, skip the quiet-gap and active-sync defers entirely; the
    // per-execution sync-XOR-broadcast rule still holds (this run never syncs).
    if (prepFastTrack) return BroadcastPreflight.BROADCAST
    if (synchronizerSyncing) return BroadcastPreflight.DEFER
    if (lastNetworkActivityEpochSeconds != null &&
        nowEpochSeconds - lastNetworkActivityEpochSeconds < privacyBufferSeconds
    ) {
        return BroadcastPreflight.DEFER
    }
    return BroadcastPreflight.BROADCAST
}

/**
 * The transaction the engine's `next_broadcastable` will actually serve: the first proved, unsent,
 * due transaction in VEC (id) order — the engine iterates its transactions vector, not the
 * schedule (documented in the engine change request §3). Null when nothing is broadcastable yet.
 */
internal fun engineBroadcastCandidate(states: MigrationTransferStates?, estimatedTip: Long): MigrationTransferState? =
    states
        ?.transfers
        ?.filter { !it.isSent && it.isProved && it.scheduledHeight <= estimatedTip }
        ?.minByOrNull { it.id }

/**
 * The earliest unsent transaction in schedule order (id as tiebreak) — the "what comes next"
 * display/pacing candidate when nothing is broadcastable yet.
 */
internal fun earliestUnsent(states: MigrationTransferStates?): MigrationTransferState? =
    states
        ?.transfers
        ?.filter { !it.isSent }
        ?.sortedWith(compareBy({ it.scheduledHeight }, { it.id }))
        ?.firstOrNull()

/**
 * True when the earliest unsent transaction is a PREPARATION that is already proved and due by
 * the estimated tip — the trigger for the preparation fast-track (see [decideBroadcastPreflight])
 * and for immediate re-chaining after a prep broadcast (no send spacing: the prep tree is one
 * logical unit; within a layer there is no one-at-a-time requirement).
 */
internal fun nextDueUnsentIsPreparation(states: MigrationTransferStates?, estimatedTip: Long): Boolean {
    val next = earliestUnsent(states) ?: return false
    return !next.isTransfer && next.isProved && next.scheduledHeight <= estimatedTip
}

/**
 * Estimated-tip broadcast acceleration predicate: a proved, unsent, non-stuck transaction whose
 * scheduled height the estimated tip has already crossed. The engine's own `nextStep` cannot see
 * past the scanned tip; this is the app-side bridge that keeps a backgrounded wallet broadcasting
 * on time (the actual send still re-verifies through the engine).
 */
internal fun broadcastDueByEstimate(states: MigrationTransferStates, estimatedTip: Long): Boolean {
    if (estimatedTip < 0L) return false
    return states.transfers.any {
        !it.isSent &&
            it.isProved &&
            it.blocker != MigrationBlocker.UNPROVABLE_ANCHOR &&
            it.scheduledHeight <= estimatedTip
    }
}

/**
 * What a genuine engine `Waiting` verdict resolves to (nextStep is Broadcast-authoritative, so a
 * due broadcast never reaches this decision — see [MigrationWorker.waitingRun]).
 */
internal enum class WaitingDisposition { COMPLETION_SWEEP, SURFACE_UNPROVABLE, RE_ARM }

internal fun waitingDisposition(allSent: Boolean, hasUnprovableBlocker: Boolean): WaitingDisposition =
    when {
        allSent -> WaitingDisposition.COMPLETION_SWEEP
        hasUnprovableBlocker -> WaitingDisposition.SURFACE_UNPROVABLE
        else -> WaitingDisposition.RE_ARM
    }

/**
 * The engine-side "when?" projection: the earliest relevant future height — the engine's next
 * sync wake-up or the next unsent transaction's scheduled height — converted to a wall-clock
 * delay at the measured block rate, floored at [MIN_REARM_SECONDS] (WorkManager slack / hot-loop
 * guard).
 *
 * Wake-ups covering ONLY unprovable-anchor transactions are excluded: the engine keeps emitting
 * immediate wake-ups for them forever (engine change request, GAP 2) and syncing can never produce
 * their proof — honoring them would hot-loop the worker at the floor delay. Returns `null` (cadence
 * fallback) when the tip estimate is unavailable or nothing relevant remains.
 *
 * Engine-only — does not know about the app's privacy quiet gap; see [nextWake], which folds this
 * in with the gap term and is what callers should use.
 */
internal fun computeEngineWakeDelay(
    states: MigrationTransferStates?,
    wakeups: List<MigrationSyncWakeup>?,
    est: Long,
    secondsPerBlock: Long,
): Duration? {
    if (est < 0L) return null
    val unprovable =
        states
            ?.transfers
            ?.filter { it.blocker == MigrationBlocker.UNPROVABLE_ANCHOR }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    val nextWakeHeight =
        wakeups
            ?.filter { wakeup -> wakeup.covers.any { it !in unprovable } }
            ?.minOfOrNull { it.height }
    val nextDueHeight =
        states
            ?.transfers
            ?.filter { !it.isSent && it.id !in unprovable }
            ?.minOfOrNull { it.scheduledHeight }
    val target = listOfNotNull(nextWakeHeight, nextDueHeight).minOrNull() ?: return null
    return ((target - est).coerceAtLeast(0L) * secondsPerBlock)
        .coerceAtLeast(MIN_REARM_SECONDS)
        .seconds
}

/**
 * The single re-arm source of truth (spec §5): `min(engine schedule, app privacy-gap expiry)`.
 * The gap is an app concept the clock-free engine cannot express — a proved, unsent transaction
 * that is already due by estimate is broadcast-ready, but if we are inside the post-sync/
 * post-broadcast quiet window, the earliest it can actually go out is `quietUntil`. Re-arming to
 * the engine height alone would ignore that wait; re-arming to the gap alone would ignore a
 * nearer engine wake (e.g. a cheaper prove). Do NOT sync while only the gap is pending — that
 * would reset it and starve the due broadcast (spec §4).
 */
internal fun nextWake(
    states: MigrationTransferStates?,
    wakeups: List<MigrationSyncWakeup>?,
    est: Long,
    secondsPerBlock: Long,
    lastActivityEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    nowEpochSeconds: Long,
): Duration? {
    val broadcastReadyGapped = states != null && broadcastDueByEstimate(states, est)
    val gapDelay =
        if (broadcastReadyGapped && lastActivityEpochSeconds != null) {
            val quietUntil = lastActivityEpochSeconds + privacyBufferSeconds
            (quietUntil - nowEpochSeconds).coerceAtLeast(0L).seconds
        } else {
            null
        }
    // When the gap term applies, it is the precise re-arm for the already-due, broadcast-ready
    // transfer(s) — exclude them from the engine's due-height floor (computeEngineWakeDelay floors
    // an already-due height at MIN_REARM_SECONDS, which could otherwise beat a longer, more
    // precise gap wait and starve it, spec §4).
    val engineStates =
        if (gapDelay != null && states != null) {
            states.copy(
                transfers =
                    states.transfers.filterNot {
                        !it.isSent && it.isProved && it.blocker != MigrationBlocker.UNPROVABLE_ANCHOR && it.scheduledHeight <= est
                    }
            )
        } else {
            states
        }
    val engineDelay = computeEngineWakeDelay(engineStates, wakeups, est, secondsPerBlock)
    return listOfNotNull(engineDelay, gapDelay).minOrNull()
}
