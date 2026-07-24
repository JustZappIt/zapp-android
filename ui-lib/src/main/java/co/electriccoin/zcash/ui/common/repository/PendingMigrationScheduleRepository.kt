package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationSchedule
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Transient, in-memory handoff of a not-yet-signed [MigrationSchedule] between the Confirm
 * Transfer Plan screen and the Keystone sign/scan screens — a Keystone account can't sign
 * in-process, so the schedule built in MigrationReviewVM has to survive the navigation to those
 * screens without round-tripping through nav args. Not persisted: if the process dies mid-flow,
 * the user re-enters from Confirm Transfer Plan and a fresh schedule is proposed.
 *
 * The schedule is stored together with the [accountKeyId] of the account that set it. [get]
 * returns `null` and clears the stored value when the caller's key id does not match the stored
 * one, preventing an account switch mid-flow from feeding one account's data into another
 * account's Keystone sign/scan path.
 */
interface PendingMigrationScheduleRepository {
    fun set(accountKeyId: String, schedule: MigrationSchedule)

    fun get(accountKeyId: String): MigrationSchedule?

    fun clear()
}

class PendingMigrationScheduleRepositoryImpl : PendingMigrationScheduleRepository {
    private val pending = MutableStateFlow<Pair<String, MigrationSchedule>?>(null)

    override fun set(accountKeyId: String, schedule: MigrationSchedule) {
        pending.value = accountKeyId to schedule
    }

    override fun get(accountKeyId: String): MigrationSchedule? {
        val current = pending.value ?: return null
        return if (current.first == accountKeyId) {
            current.second
        } else {
            pending.value = null
            null
        }
    }

    override fun clear() {
        pending.value = null
    }
}
