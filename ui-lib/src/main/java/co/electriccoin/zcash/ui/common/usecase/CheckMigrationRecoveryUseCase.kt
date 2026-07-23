package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
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
) {
    suspend operator fun invoke() {
        // No wallet yet (e.g. a fresh install before onboarding) — this runs on every
        // MainActivity launch regardless, so treat "no SDK available" as "nothing to recover".
        val sdk = getOrchardMigrationSdk() ?: return
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
        } else if (sdk.hasInvalidTransfers()) {
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: invalid transfer detected — redirecting to Transfer Invalid." }
            navigationRouter.replaceAll(HomeArgs, MigrationTransferInvalidArgs)
        } else if (isTransferReadyToSendWithoutBackground(sdk)) {
            Twig.debug {
                "MIGRATION_DIAG MigrationRecovery: transfer due, background execution unavailable — " +
                    "redirecting to Transfer Review."
            }
            navigationRouter.replaceAll(HomeArgs, MigrationTransferReviewArgs)
        } else if (sdk.hasOverdueTransfers()) {
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
            navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
        } else if (sdk.getMigrationState() == MigrationState.Complete &&
            !hasSeenMigrationCompleteStorageProvider.get() &&
            migrationPlanRepository.load() != null &&
            // Truly complete, not just "this round's transfers are all mined" — a multi-round
            // Keystone migration reports Complete at every round boundary even with a large
            // residual balance still needing another round (see
            // MIGRATION_DUST_THRESHOLD_ZATOSHI's kdoc). Routing to the celebration screen at that
            // point would also dangerously offer "Lock balance" on an above-threshold balance.
            getOrchardBalance().value <= MIGRATION_DUST_THRESHOLD_ZATOSHI
        ) {
            // A fresh install / a wallet that never needed to migrate never reaches
            // MigrationState.Complete — that requires a MigrationPlan to have existed and finished,
            // so this can never fire for them.
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: migration just completed — showing one-time celebration." }
            navigationRouter.replaceAll(HomeArgs, MigrationCompleteArgs)
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
