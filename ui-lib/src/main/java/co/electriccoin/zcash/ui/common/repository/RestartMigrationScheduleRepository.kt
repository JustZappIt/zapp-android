package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationSchedule
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Transient, in-memory handoff of the [MigrationSchedule] returned by
 * [cash.z.ecc.android.sdk.OrchardMigrationSdk.restartCurrentMigrationStep] — that method's own doc
 * requires its returned schedule to go through the normal user confirmation flow
 * (MigrationReviewVM → signAndStoreMigrationSchedule), not to be silently discarded in favor of an
 * independently re-proposed one (the two calls compute independent guesses over the same balance
 * and are not guaranteed to agree, same reasoning as proposeMigrationTransfersFromSplit's doc).
 *
 * `MigrationTransferInvalidVM.onContinue()` sets this right before navigating to the Confirm
 * Transfer Plan screen; `MigrationReviewVM` consumes (reads-and-clears) it once, at init, falling
 * back to a fresh `proposeMigrationTransfers()` call when nothing is pending — the ordinary,
 * non-recovery entry point.
 *
 * Deliberately a separate slot from [PendingMigrationScheduleRepository] (that one's Keystone
 * sign/scan hand-off, one step further down the same screen) — the two flows can run back-to-back
 * (restart → Review → Keystone sign) inside a single confirmation, and keeping them in separate
 * slots means an abandoned Keystone attempt's leftover state can never be mistaken for a pending
 * restart schedule on some later, unrelated Review entry.
 */
interface RestartMigrationScheduleRepository {
    fun set(schedule: MigrationSchedule)

    /** Reads and clears the pending schedule in one step — consumed at most once. */
    fun consume(): MigrationSchedule?
}

class RestartMigrationScheduleRepositoryImpl : RestartMigrationScheduleRepository {
    private val pending = MutableStateFlow<MigrationSchedule?>(null)

    override fun set(schedule: MigrationSchedule) {
        pending.value = schedule
    }

    override fun consume(): MigrationSchedule? = pending.value.also { pending.value = null }
}
