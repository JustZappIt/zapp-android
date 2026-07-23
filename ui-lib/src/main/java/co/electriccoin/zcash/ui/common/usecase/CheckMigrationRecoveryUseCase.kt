package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs

/**
 * Single source of truth for migration re-entry routing on app launch/foreground — MainActivity's
 * onStart() and RootNavGraph's secretState-driven redirect both delegate here instead of calling
 * the SDK checks directly, so the two never drift out of sync with each other or with this
 * ordering. Cheap and idempotent (NavigationRouter dedupes identical commands).
 *
 * Checks invalid transfers before overdue ones, per spec §4.3 — a plan that needs to be
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
 */
class CheckMigrationRecoveryUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val navigationRouter: NavigationRouter,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val migrationPlanRepository: MigrationPlanRepository,
) {
    suspend operator fun invoke() {
        // No wallet yet (e.g. a fresh install before onboarding) — this runs on every
        // MainActivity launch regardless, so treat "no SDK available" as "nothing to recover".
        val sdk = getOrchardMigrationSdk() ?: return
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
        if (requiresAttention) {
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: attention required — redirecting to Transfer Invalid." }
            navigationRouter.replaceAll(HomeArgs, MigrationTransferInvalidArgs)
        } else if (sdk.hasOverdueTransfers()) {
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
            navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
        } else if (migrationState == MigrationState.Complete &&
            !hasSeenMigrationCompleteStorageProvider.get() &&
            migrationPlanRepository.load() != null
        ) {
            // A fresh install / a wallet that never needed to migrate never reaches
            // MigrationState.Complete — that requires a MigrationPlan to have existed and finished,
            // so this can never fire for them.
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: migration just completed — showing one-time celebration." }
            navigationRouter.replaceAll(HomeArgs, MigrationCompleteArgs)
        }
    }
}
