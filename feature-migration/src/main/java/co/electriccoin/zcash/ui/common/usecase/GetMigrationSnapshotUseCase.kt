package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.toSnapshot
import kotlin.time.Clock

/**
 * The one way display consumers read the migration plan: a live [LiveMigrationSnapshot] derived
 * from the engine's persisted state (nothing app-persisted — see
 * `spec/2026-07-30-plan-cache-elimination-proposal.md`). Returns `null` when the account has no
 * committed migration.
 */
class GetMigrationSnapshotUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
) {
    suspend operator fun invoke(accountKeyId: String? = null): LiveMigrationSnapshot? {
        val sdk =
            (if (accountKeyId != null) getOrchardMigrationSdk(accountKeyId) else getOrchardMigrationSdk())
                ?: return null
        val states = sdk.getMigrationTransferStates() ?: return null
        return states.toSnapshot(
            estimatedTip = sdk.estimatedChainTip(),
            secondsPerBlock = sdk.estimatedSecondsPerBlock(),
            nowEpochSeconds = Clock.System.now().epochSeconds,
        )
    }
}
