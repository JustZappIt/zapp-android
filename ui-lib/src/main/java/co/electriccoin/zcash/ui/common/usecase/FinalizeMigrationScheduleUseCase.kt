package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationSchedule
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationKeystoneRound
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.estimatedSecondsBetweenHeights
import co.electriccoin.zcash.ui.common.model.migration.toMigrationPlan
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import co.electriccoin.zcash.work.MigrationScheduler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Persists a signed [MigrationSchedule] and schedules the background worker for its first
 * transfer, then navigates to [MigrationScheduledArgs]. Shared by both the hot-wallet confirm path
 * (MigrationReviewVM) and the post-Keystone-scan path (MigrationKeystoneScanVM) so the scheduling
 * logic isn't duplicated.
 *
 * Background delivery is scheduled unconditionally, regardless of whether the user granted the
 * Battery-optimization-exemption permission — declining it only makes background execution less
 * reliable (may be deferred by Doze), it does not disable it. Whatever the OS/system still prevents
 * is caught by [MigrationWorker][co.electriccoin.zcash.work.MigrationWorker]'s own retry-on-not-ready
 * behavior and by on-launch reconciliation
 * ([CheckMigrationRecoveryUseCase][co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase]),
 * not by a separate notify-only delivery mode.
 */
class FinalizeMigrationScheduleUseCase(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val migrationScheduler: MigrationScheduler,
    private val navigationRouter: NavigationRouter,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
) {
    suspend operator fun invoke(sched: MigrationSchedule, mode: MigrationMode) {
        persistPlan(sched, mode)
        migrationScheduler.schedule(delayUntilFirstTransfer(sched))
        navigationRouter.forward(MigrationScheduledArgs)
    }

    /**
     * Write-ahead persistence of the app-side plan, called BEFORE the irreversible SDK commit
     * (`submitNoteSplit`/`signAndStoreMigrationSchedule`) in MigrationReviewVM — not just at the end
     * via [invoke].
     *
     * `OrchardMigrationSdk.getMigrationState()` is the source of truth for "has this migration
     * committed", but the app-side plan is what the home banner and progress screen actually read.
     * Persisting the plan before the commit means a crash in the window between that commit and
     * [invoke]'s worker-schedule/navigation leaves a *recoverable* state — `InProgress` + a saved
     * plan, which re-entry resumes to the progress screen — rather than a plan-less `InProgress` the
     * app mistakes for a fresh start and tries to re-commit (which re-finalizes the already-broadcast
     * split and fails). A commit that never actually happens (SDK still `NotStarted`) leaves a stale
     * plan, reconciled away by
     * [CheckMigrationRecoveryUseCase][co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase].
     */
    suspend fun persistPlan(sched: MigrationSchedule, mode: MigrationMode) {
        // Stateless preview, computed fresh here rather than threaded through from Review — see
        // MigrationKeystoneRound's kdoc. Never persisted as a running campaign counter: "current" is
        // always 1 ("this round, from here"), "total" is whatever the estimate says right now.
        val keystoneRound = if (getSelectedWalletAccount() is KeystoneAccount) {
            getOrchardMigrationSdk()?.estimateMigrationRunCount()?.takeIf { it > 1 }?.let { MigrationKeystoneRound(current = 1, total = it) }
        } else {
            null
        }
        migrationPlanRepository.save(sched.toMigrationPlan(mode, keystoneRound))
    }

    // The first transfer is never "ready now" (same anchor/proposal round trip as any other
    // transfer, per proposeMigrationTransfers()) — the very first WorkManager job must wait for
    // it just like every job scheduled after it, not fire immediately.
    //
    // nextExecutableAfterHeight/anchorHeight/expiryHeight are block heights, not timestamps — see
    // estimatedSecondsBetweenHeights for why they must never be used directly as (or against)
    // epoch seconds (this previously made every transfer look ~56 years overdue on a live device).
    private fun delayUntilFirstTransfer(sched: MigrationSchedule): Duration {
        val first = sched.transfers.minByOrNull { it.nextExecutableAfterHeight } ?: return 0.seconds
        val remaining = estimatedSecondsBetweenHeights(first.anchorHeight, first.nextExecutableAfterHeight)
        return if (remaining <= 0) 0.seconds else remaining.seconds
    }
}
