package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationSyncScheduler

/**
 * Called on every foreground Status.SYNCED transition while a migration plan is active.
 * Finalizes any transfers whose funding note became witnessed since the last sync, then
 * checks for invalidations — if any, notifies the user and cancels both background lanes
 * (Lane A sync heartbeat and Lane B execution). Always stamps the last-network-activity
 * timestamp so Lane B's privacy-buffer gap calculation is accurate — even on invalidation,
 * the sync itself still happened.
 */
class OnMigrationSyncCompletedUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
    private val migrationNotifier: MigrationNotifier,
    private val migrationScheduler: MigrationScheduler,
    private val migrationSyncScheduler: MigrationSyncScheduler,
) {
    suspend operator fun invoke(accountKeyId: String) {
        val sdk = getOrchardMigrationSdk(accountKeyId) ?: return
        sdk.finalizeReadyTransfers()
        if (sdk.reconcileInvalidations()) {
            migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
            migrationScheduler.cancel(accountKeyId)
            migrationSyncScheduler.cancel(accountKeyId)
        }
        lastNetworkActivity.stampNow()
    }
}
