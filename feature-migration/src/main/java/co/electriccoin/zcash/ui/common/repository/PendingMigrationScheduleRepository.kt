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

    /**
     * Reads and clears the stored schedule in one step.
     * Returns `null` **and clears** the stored value when the stored account key id differs from
     * [accountKeyId]. Use only for one-shot, non-reactive consumption.
     *
     * **Do NOT call this inside a reactive context** (e.g. inside a `combine`, `map`, or `flow`
     * block): on an account-mismatch emission the clearing side-effect would permanently destroy
     * the schedule while the correct account emission is still pending. Use [peek] inside reactive
     * contexts instead.
     */
    fun get(accountKeyId: String): MigrationSchedule?

    /**
     * Non-mutating read: returns the stored schedule if the stored account key id matches
     * [accountKeyId], else `null`. **Never clears or mutates the stored value** — safe to call
     * inside reactive contexts such as `combine` or `map` where the same block may be re-evaluated
     * on multiple emissions.
     */
    fun peek(accountKeyId: String): MigrationSchedule?

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

    override fun peek(accountKeyId: String): MigrationSchedule? {
        val current = pending.value ?: return null
        return if (current.first == accountKeyId) current.second else null
    }

    override fun clear() {
        pending.value = null
    }
}
