package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationSchedule
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Transient, in-memory handoff of a not-yet-signed [MigrationSchedule] between the Confirm
 * Transfer Plan screen and the Keystone sign/scan screens — a Keystone account can't sign
 * in-process, so the schedule built in MigrationReviewVM has to survive the navigation to those
 * screens without round-tripping through nav args. Not persisted: if the process dies mid-flow,
 * the user re-enters from Confirm Transfer Plan and a fresh schedule is proposed.
 */
interface PendingMigrationScheduleRepository {
    fun set(schedule: MigrationSchedule)

    fun get(): MigrationSchedule?

    fun clear()
}

class PendingMigrationScheduleRepositoryImpl : PendingMigrationScheduleRepository {
    private val pending = MutableStateFlow<MigrationSchedule?>(null)

    override fun set(schedule: MigrationSchedule) {
        pending.value = schedule
    }

    override fun get(): MigrationSchedule? = pending.value

    override fun clear() {
        pending.value = null
    }
}
