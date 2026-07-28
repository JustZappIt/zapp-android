package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.ui.screen.migration.transferreview.MigrationTransferReviewArgs
import co.electriccoin.zcash.work.MigrationSyncScheduler
import co.electriccoin.zcash.work.WorkIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Single source of truth for migration re-entry routing on app launch/foreground — MainActivity's
 * onStart() and RootNavGraph's secretState-driven redirect both delegate here instead of calling
 * the SDK checks directly, so the two never drift out of sync with each other or with this
 * ordering. Cheap and idempotent (NavigationRouter dedupes identical commands).
 *
 * Checks a pending background Tor failure before everything else — see
 * [PendingMigrationTorFailureStorageProvider] — since re-entering the Sending screen naturally
 * reproduces (and, via its own existing routing, resolves or re-surfaces) that specific failure
 * mode. Checks invalid transfers before overdue ones, per spec §4.3 — a plan that needs to be
 * re-created takes priority over merely resuming a stale schedule. The one-time Migration
 * Complete celebration screen is lowest priority — it's non-actionable, so it never preempts an
 * actual problem needing attention.
 *
 * The Complete-celebration branch additionally requires a [MigrationPlanRepository] plan to still
 * exist. `MigrationState.Complete` alone isn't enough: for a Keystone account still mid-campaign,
 * `MigrationCompleteVM.onDone()` clears the plan (without setting the seen-flag) to let the home
 * banner naturally re-offer the next round, but the SDK's own [MigrationState] stays `Complete`
 * until that next round is actually committed. Requiring `migrationPlanRepository.load() != null`
 * here means this only fires for a genuinely fresh, not-yet-acknowledged completion — not on every
 * relaunch between Keystone rounds.
 *
 * An overdue transfer always routes to the fuller Resume Migration screen with Send Now/Reschedule
 * — background delivery is scheduled unconditionally (see `FinalizeMigrationScheduleUseCase`), so
 * "overdue" always means the background worker hasn't broadcast it yet, whether because it hasn't
 * fired (Doze deferral) or the transfer wasn't ready when it last ran.
 *
 * Deliberately does NOT auto-execute the overdue transfer (that used to happen here via an
 * immediate WorkManager schedule) — the user must explicitly choose to send or reschedule. Sync is
 * already stopped independently of this, via OrchardMigrationSdk.isSyncBlocked() feeding directly
 * into the synchronizer.
 *
 * A ready-to-send check (spec §6.4) is inserted BEFORE the overdue-transfer branch — a transfer
 * that's due but background execution never had a chance to run it takes priority over (and is
 * narrower than) the general overdue/missed-transfer state, matching the same condition
 * `GetHomeMessageUseCase.migrationMessageFor()` uses for the home banner.
 */
class CheckMigrationRecoveryUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val navigationRouter: NavigationRouter,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val isBackgroundExecutionAvailableProvider: IsBackgroundExecutionAvailableProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val migrationSyncScheduler: MigrationSyncScheduler,
    private val context: Context,
    /** Extracted for testability — production default checks WorkManager. */
    private val isLaneAActive: suspend () -> Boolean = { isLaneAActiveInWorkManager(context) },
    /** Lane B twin, same testability rationale. */
    private val isLaneBActive: suspend (String) -> Boolean = { isLaneBActiveInWorkManager(context, it) },
) {
    suspend operator fun invoke() {
        // Three independent triggers exist (MainActivity.onStart, RootNavGraph unlock, and any
        // future caller); without a throttle they cascade — each replaceAll builds a fresh Home
        // entry whose composition can re-trigger recovery, observed live as 7 redirects (and 3
        // duplicate catch-up shifts) within 8 seconds. One pass per window is enough: routing is
        // idempotent for the user and isSyncBlocked() protects sync regardless.
        val nowMs = android.os.SystemClock.elapsedRealtime()
        synchronized(CheckMigrationRecoveryUseCase) {
            if (nowMs - lastRunElapsedMs < RUN_THROTTLE_MS) {
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: throttled (ran ${nowMs - lastRunElapsedMs}ms ago)" }
                return
            }
            lastRunElapsedMs = nowMs
        }
        // No wallet YET — on a cold start this fires before the synchronizer initializes, and
        // silently consuming the throttle window here left recovery permanently ineffective
        // (observed live: 1st call = SDK null + throttle stamped, 2nd call 3s later = throttled;
        // both lanes stayed dead after a reinstall). Un-stamp the throttle so the next trigger
        // (foreground/unlock/onStart all re-fire) gets a real attempt once the wallet is up.
        val sdk = getOrchardMigrationSdk() ?: run {
            synchronized(CheckMigrationRecoveryUseCase) { lastRunElapsedMs = 0L }
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: SDK not ready — will retry on next trigger." }
            return
        }

        // (a) Lane A reconciliation — if a plan exists but the Lane A unique work is absent
        // (ENQUEUED or RUNNING), re-schedule it. This self-heals after process kill, device
        // reboot, or an app upgrade that cleared WorkManager state, without requiring the user to
        // re-enter the migration flow.
        // Gate on the ENGINE's state, not only the app-side plan cache: the cache can be lost
        // (observed live: repository empty while the engine held a run with 8/9 broadcast and the
        // last transfer proved) and the engine is the single source of truth — a live in-progress
        // migration must always have its lanes running.
        val engineInProgress = sdk.getMigrationState() is MigrationState.InProgress
        if (migrationPlanRepository.load() != null || engineInProgress) {
            val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
            if (!isLaneAActive()) {
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: Lane A absent, re-scheduling." }
                // A short flat first arm: the schedule object carries no plan knowledge — the
                // worker's first run reads the live engine states and computes the precise
                // boundary-driven wake itself (see MigrationSyncWorker).
                migrationSyncScheduler.schedule(accountKeyId, 60.seconds)
            }
            // Lane B revival too — its re-arm only happens at the end of its own run and its due
            // alarms don't survive a package update, so an update mid-plan otherwise kills every
            // future broadcast (see OnMigrationSyncCompletedUseCase; duplicated here because the
            // SYNCED hook needs a synced foreground synchronizer, which a freshly relaunched app
            // may not reach for minutes).
            if (!isLaneBActive(accountKeyId)) {
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: Lane B absent, re-scheduling." }
                co.electriccoin.zcash.work.MigrationScheduler(context).schedule(accountKeyId, 60.seconds)
            }
        }
        // Read the real state once instead of the old hasInvalidTransfers() boolean — the app must
        // distinguish AttentionReason.InvalidTransfer (spec §6.2, external spend invalidated the
        // plan) from AttentionReason.TransferExpired (spec §6.3, missed window) once inside the
        // Transfer Invalid screen, and that screen re-reads getMigrationState() itself as its own
        // source of truth. SyncRequiredBeforeNext is deliberately excluded here — nothing in the
        // app surfaces that reason yet (see MigrationAttentionKind's doc), so it is left exactly as
        // unhandled as it was before this change, rather than being routed to a screen that doesn't
        // have copy for it.
        val migrationState = sdk.getMigrationState()
        val requiresAttention = migrationState is MigrationState.RequiresAttention &&
            migrationState.reason !is AttentionReason.SyncRequiredBeforeNext
        if (pendingMigrationTorFailureStorageProvider.get()) {
            // A background attempt failed specifically because of Tor — route through the Sending
            // screen first rather than straight to MigrationProgressArgs/MigrationTorFailureArgs:
            // MigrationSendingVM's init{} always attempts a send immediately on construction,
            // reproducing the exact condition that failed in the background using the current
            // migration Tor setting. If it fails again, MigrationSendingVM's own existing
            // sendOnce() logic already forwards to MigrationTorFailureArgs — no need to duplicate
            // that routing here.
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: pending background Tor failure — redirecting to Sending." }
            navigationRouter.replaceAll(HomeArgs, MigrationSendingArgs)
        } else if (requiresAttention) {
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: attention required — redirecting to Transfer Invalid." }
            navigationRouter.replaceAll(HomeArgs, MigrationTransferInvalidArgs)
        } else if (isTransferReadyToSendWithoutBackground(sdk)) {
            Twig.debug {
                "MIGRATION_DIAG MigrationRecovery: transfer due, background execution unavailable — " +
                    "redirecting to Transfer Review."
            }
            navigationRouter.replaceAll(HomeArgs, MigrationTransferReviewArgs)
        } else if (sdk.hasOverdueTransfers()) {
            // No catch-up shifting here: the plan stays exactly as the engine committed it (the
            // engine is the single source of truth); overdue transfers are served
            // one-per-broadcast in the engine's own order, paced by the post-broadcast privacy
            // buffer.
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
            navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
        } else if (migrationState == MigrationState.Complete &&
            !hasSeenMigrationCompleteStorageProvider.get() &&
            migrationPlanRepository.load() != null &&
            // Truly complete, not just "this round's transfers are all mined" — a multi-round
            // Keystone migration reports Complete at every round boundary even with a large
            // residual balance still needing another round. Routing to the celebration screen at
            // that point would also dangerously offer "Lock balance" on an above-threshold balance.
            getOrchardBalance().value <= sdk.migrationDustThresholdZatoshi()
        ) {
            // A fresh install / a wallet that never needed to migrate never reaches
            // MigrationState.Complete — that requires a MigrationPlan to have existed and finished,
            // so this can never fire for them.
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: migration just completed — showing one-time celebration." }
            navigationRouter.replaceAll(HomeArgs, MigrationCompleteArgs)
        } else if (migrationState == MigrationState.NotStarted && migrationPlanRepository.load() != null) {
            // A stale write-ahead plan: MigrationReviewVM.confirmAutomatic persists the plan just
            // before the irreversible SDK commit (see FinalizeMigrationScheduleUseCase.persistPlan),
            // so if that commit never actually happened — submitNoteSplit()/signAndStoreMigrationSchedule()
            // threw before commit_preparation, leaving the SDK NotStarted — the plan is left behind
            // pointing at a migration that doesn't exist. The SDK state is authoritative, so discard
            // it rather than letting the home banner offer to "resume" a phantom migration. (An
            // actually-committed migration reports InProgress here, not NotStarted, and is left
            // untouched — its saved plan is real and drives the progress screen.)
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: stale write-ahead plan, SDK NotStarted — clearing." }
            migrationPlanRepository.clear()
        }
    }

    // Same condition GetHomeMessageUseCase.migrationMessageFor() uses for the home banner: the
    // next pending transfer's scheduled time has arrived, background execution can't run it, and
    // the SDK doesn't (yet) count it as overdue — a narrower, earlier window than the general
    // overdue branch above.
    companion object {
        private const val RUN_THROTTLE_MS = 10_000L

        @Volatile
        private var lastRunElapsedMs = Long.MIN_VALUE / 2

        /** Tests run in one JVM — reset the shared throttle between them. */
        internal fun resetRunThrottleForTests() {
            lastRunElapsedMs = Long.MIN_VALUE / 2
        }
    }

    private suspend fun isTransferReadyToSendWithoutBackground(sdk: OrchardMigrationSdk): Boolean {
        if (isBackgroundExecutionAvailableProvider.isAvailable()) return false
        val next = migrationPlanRepository.load()?.nextPending ?: return false
        if (next.scheduledAt > Clock.System.now()) return false
        return !sdk.hasOverdueTransfers()
    }
}

/**
 * Production implementation of the Lane A active check — reads WorkManager unique work state.
 * Extracted so [CheckMigrationRecoveryUseCase] tests can supply a lambda stub instead of
 * needing a real WorkManager context (unit tests can't initialise WorkManager).
 */
internal suspend fun isLaneAActiveInWorkManager(context: Context): Boolean =
    withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkIds.WORK_ID_MIGRATION_SYNC)
            .get()
    }.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

/** Lane B (broadcast) twin of [isLaneAActiveInWorkManager] — per-account unique work name. */
internal suspend fun isLaneBActiveInWorkManager(context: Context, accountKeyId: String): Boolean =
    withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(co.electriccoin.zcash.work.MigrationScheduler.workId(accountKeyId))
            .get()
    }.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
