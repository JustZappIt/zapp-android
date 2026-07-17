package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationDeliveryMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import co.electriccoin.zcash.work.MigrationScheduler
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Persists a signed [MigrationSchedule] and hands it off to either the background scheduler or an
 * immediate foreground send, then navigates to [MigrationScheduledArgs]. Shared by both the
 * hot-wallet confirm path (MigrationReviewVM) and the post-Keystone-scan path
 * (MigrationKeystoneScanVM) so the delivery-mode/scheduling logic isn't duplicated.
 *
 * Returns null on success (already navigated onward). Returns the failing [TransferResult]
 * otherwise so the caller can surface its own failure sheet and retry by calling this again.
 */
class FinalizeMigrationScheduleUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val migrationScheduler: MigrationScheduler,
    private val scheduleNextMigrationWindow: ScheduleNextMigrationWindowUseCase,
    private val navigationRouter: NavigationRouter,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
) {
    suspend operator fun invoke(
        sched: MigrationSchedule,
        mode: MigrationMode,
        backgroundAvailable: Boolean,
    ): TransferResult? {
        // Background delivery unavailable (Battery screen declined) — fall back to MANUAL:
        // send transfer #1 immediately in the foreground now, then only ever notify (never
        // silently auto-send) for subsequent transfers.
        val deliveryMode = if (backgroundAvailable) MigrationDeliveryMode.SCHEDULED else MigrationDeliveryMode.MANUAL
        migrationPlanRepository.save(sched.toMigrationPlan(mode, deliveryMode))

        if (deliveryMode == MigrationDeliveryMode.MANUAL) {
            // Reset before sending (not after) — if the process dies mid-send, the persisted
            // plan must already read as overdue on relaunch, not just after a full interval.
            migrationPlanRepository.rescheduleTransfer(0, Clock.System.now().epochSeconds)
            val useTor = isTorEnabledStorageProvider.get() == true
            return when (
                val result =
                    getOrchardMigrationSdk()?.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor))
            ) {
                is TransferResult.Success -> {
                    scheduleNextMigrationWindow()
                    navigationRouter.forward(MigrationScheduledArgs)
                    null
                }
                null -> {
                    navigationRouter.forward(MigrationScheduledArgs)
                    null
                }
                else -> result
            }
        } else {
            migrationScheduler.schedule(delayUntilFirstTransfer(sched))
            navigationRouter.forward(MigrationScheduledArgs)
            return null
        }
    }

    // The first transfer is never "ready now" (same anchor/proposal round trip as any other
    // transfer, per proposeMigrationTransfers()) — the very first WorkManager job must wait for
    // it just like every job scheduled after it, not fire immediately.
    private fun delayUntilFirstTransfer(sched: MigrationSchedule): Duration {
        val firstAt = sched.transfers.minOfOrNull { it.nextExecutableAfterHeight } ?: return 0.seconds
        return delayUntil(firstAt)
    }

    private fun delayUntil(epochSeconds: Long): Duration {
        val remaining = epochSeconds - Clock.System.now().epochSeconds
        return if (remaining <= 0) 0.seconds else remaining.seconds
    }

    private fun MigrationSchedule.toMigrationPlan(
        mode: MigrationMode,
        deliveryMode: MigrationDeliveryMode,
    ) = MigrationPlan(
        id = UUID.randomUUID().toString(),
        createdAtEpochSeconds = Clock.System.now().epochSeconds,
        transfers = transfers.mapIndexed { i, t ->
            MigrationTransfer(
                index = i,
                amountZatoshi = t.amountZatoshi,
                scheduledAtEpochSeconds = t.nextExecutableAfterHeight,
                status = MigrationTransferStatus.PENDING,
                expiryAtEpochSeconds = t.expiryHeight,
            )
        },
        mode = mode,
        deliveryMode = deliveryMode,
    )
}
