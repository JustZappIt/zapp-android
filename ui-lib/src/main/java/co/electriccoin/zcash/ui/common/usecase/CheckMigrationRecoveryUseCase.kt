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
import co.electriccoin.zcash.work.laneACadence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

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
) {
    suspend operator fun invoke() {
        // No wallet yet (e.g. a fresh install before onboarding) — this runs on every
        // MainActivity launch regardless, so treat "no SDK available" as "nothing to recover".
        val sdk = getOrchardMigrationSdk() ?: return

        // (a) Lane A reconciliation — if a plan exists but the Lane A unique work is absent
        // (ENQUEUED or RUNNING), re-schedule it. This self-heals after process kill, device
        // reboot, or an app upgrade that cleared WorkManager state, without requiring the user to
        // re-enter the migration flow.
        if (migrationPlanRepository.load() != null && !isLaneAActive()) {
            val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: Lane A absent, re-scheduling." }
            migrationSyncScheduler.schedule(accountKeyId, laneACadence())
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
            // (b) Overdue catch-up: keep the earliest overdue transfer (the engine will offer it
            // as the immediate candidate), and shift the rest to future windows so only one
            // transfer is ever broadcasting at a time. Proved transfers return -1 from
            // rescheduleUnprovenTransfer — they cannot be shifted; the engine will offer them
            // one-per-broadcast with the existing post-broadcast buffer (accepted residual until
            // a core primitive exists).
            val liveStates = sdk.getMigrationTransferStates()
            val tipHeight = liveStates?.tipHeight ?: sdk.estimatedChainTip()
            val overdueIds = liveStates?.transfers
                ?.filter { !it.isSent && it.scheduledHeight <= tipHeight }
                ?.sortedBy { it.scheduledHeight }
                ?.map { it.id }
                .orEmpty()
            for (id in overdueTransfersToShift(overdueIds)) {
                val result = sdk.rescheduleUnprovenTransfer(id)
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: at-most-one catch-up: shifted $id to $result" }
            }
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
    private suspend fun isTransferReadyToSendWithoutBackground(sdk: OrchardMigrationSdk): Boolean {
        if (isBackgroundExecutionAvailableProvider.isAvailable()) return false
        val next = migrationPlanRepository.load()?.nextPending ?: return false
        if (next.scheduledAt > Clock.System.now()) return false
        return !sdk.hasOverdueTransfers()
    }
}

/**
 * Spec §5 B2 "at-most-one-overdue" catch-up: given the pending (not-yet-sent) transfers sorted
 * ascending by scheduled height, keep the earliest one (the engine's natural next candidate) and
 * return the rest for rescheduling to future windows. Empty and single-element lists return empty
 * — nothing to shift.
 */
internal fun overdueTransfersToShift(pendingSortedByHeight: List<String>): List<String> =
    pendingSortedByHeight.drop(1)

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
