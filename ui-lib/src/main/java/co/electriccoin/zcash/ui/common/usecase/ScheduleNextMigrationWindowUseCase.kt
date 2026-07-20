package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.work.MigrationScheduler
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Re-arms the background worker for whatever transfer is next in the active plan, after any
 * transfer broadcasts successfully (scheduled, resumed, or manually confirmed). No-ops if there's
 * no next pending transfer (e.g. the plan just completed, or IMMEDIATE mode's single-transfer
 * plan). Background delivery is scheduled unconditionally — see [MigrationScheduler]/
 * [FinalizeMigrationScheduleUseCase] for why this no longer depends on a delivery-mode flag.
 */
class ScheduleNextMigrationWindowUseCase(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val migrationScheduler: MigrationScheduler,
) {
    suspend operator fun invoke() {
        val plan = migrationPlanRepository.load() ?: return
        val next = plan.nextPending ?: return
        val delay = delayUntil(next.scheduledAtEpochSeconds)
        migrationScheduler.schedule(delay)
    }

    private fun delayUntil(epochSeconds: Long): Duration {
        val remaining = epochSeconds - Clock.System.now().epochSeconds
        return if (remaining <= 0) 0.seconds else remaining.seconds
    }
}
