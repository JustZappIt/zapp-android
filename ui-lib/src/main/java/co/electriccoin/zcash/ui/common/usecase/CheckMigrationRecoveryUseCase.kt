package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs

/**
 * Single source of truth for migration re-entry routing on app launch/foreground — MainActivity's
 * onStart() and RootNavGraph's secretState-driven redirect both delegate here instead of calling
 * the SDK checks directly, so the two never drift out of sync with each other or with this
 * ordering. Cheap and idempotent (NavigationRouter dedupes identical commands).
 *
 * Checks invalid transfers before overdue ones, per spec §4.3 — a plan that needs to be
 * re-created takes priority over merely resuming a stale schedule.
 *
 * Deliberately does NOT auto-execute the overdue transfer (that used to happen here via an
 * immediate WorkManager schedule) — the user must explicitly choose Send Now or Reschedule on
 * the Resume Migration screen. Sync is already stopped independently of this, via
 * OrchardMigrationSdk.isSyncBlocked() feeding directly into the synchronizer.
 */
class CheckMigrationRecoveryUseCase(
    private val sdk: OrchardMigrationSdk,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        if (sdk.hasInvalidTransfers()) {
            Twig.debug { "MigrationRecovery: invalid transfer detected — redirecting to Transfer Invalid." }
            navigationRouter.replaceAll(HomeArgs, MigrationTransferInvalidArgs)
        } else if (sdk.hasOverdueTransfers()) {
            Twig.debug { "MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
            navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
        }
    }
}
