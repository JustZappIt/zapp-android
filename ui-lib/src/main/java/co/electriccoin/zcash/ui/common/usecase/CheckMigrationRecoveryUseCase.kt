package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationState
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationDeliveryMode
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.transferreview.MigrationTransferReviewArgs

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
 * An overdue transfer routes to one of two different screens depending on delivery mode: for a
 * MANUAL plan, becoming "due" is routine (nothing ever auto-sends it), so it's just the normal
 * "your turn to confirm" case — a lean single-transfer Review Transfer screen with no reschedule
 * option. For a SCHEDULED plan, overdue means the background worker failed to fire — a real
 * catch-up/error case — so it goes to the fuller Resume Migration screen with Send Now/Reschedule.
 *
 * Deliberately does NOT auto-execute the overdue transfer (that used to happen here via an
 * immediate WorkManager schedule) — the user must explicitly choose to send or reschedule. Sync is
 * already stopped independently of this, via OrchardMigrationSdk.isSyncBlocked() feeding directly
 * into the synchronizer.
 */
class CheckMigrationRecoveryUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val navigationRouter: NavigationRouter,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
) {
    suspend operator fun invoke() {
        // No wallet yet (e.g. a fresh install before onboarding) — this runs on every
        // MainActivity launch regardless, so treat "no SDK available" as "nothing to recover".
        val sdk = getOrchardMigrationSdk() ?: return
        if (sdk.hasInvalidTransfers()) {
            Twig.debug { "MigrationRecovery: invalid transfer detected — redirecting to Transfer Invalid." }
            navigationRouter.replaceAll(HomeArgs, MigrationTransferInvalidArgs)
        } else if (sdk.hasOverdueTransfers()) {
            val plan = migrationPlanRepository.load()
            if (plan?.deliveryMode == MigrationDeliveryMode.MANUAL) {
                Twig.debug { "MigrationRecovery: manual transfer due — redirecting to Review Transfer." }
                navigationRouter.replaceAll(HomeArgs, MigrationTransferReviewArgs)
            } else {
                Twig.debug { "MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
                navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
            }
        } else if (sdk.getMigrationState() == MigrationState.Complete && !hasSeenMigrationCompleteStorageProvider.get()) {
            // A fresh install / a wallet that never needed to migrate never reaches
            // MigrationState.Complete — that requires a MigrationPlan to have existed and finished,
            // so this can never fire for them.
            Twig.debug { "MigrationRecovery: migration just completed — showing one-time celebration." }
            navigationRouter.replaceAll(HomeArgs, MigrationCompleteArgs)
        }
    }
}
