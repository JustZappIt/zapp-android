package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs

/**
 * Third, redundant catch (alongside RootNavGraph's secretState-driven redirect and
 * MainActivity.onStart()) for redirecting to Resume Migration when a transfer is overdue —
 * cheap and idempotent (NavigationRouter dedupes identical commands), protects against any
 * race where Home gets composed before the other two hooks run.
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
        if (sdk.hasOverdueTransfers()) {
            Twig.debug { "MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
            navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
        }
    }
}
