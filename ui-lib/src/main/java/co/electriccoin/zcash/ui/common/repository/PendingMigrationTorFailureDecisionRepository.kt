package co.electriccoin.zcash.ui.common.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transient, in-memory handoff of the user's choice from the "Couldn't Connect to Tor" sheet
 * (`MigrationTorFailureScreen`) back to whichever migration send call site pushed that route —
 * the sheet is a standalone nav destination (not an inline conditional composable like
 * [co.electriccoin.zcash.ui.screen.migration.component.MigrationFailureBottomSheet]), so its
 * result can't just be a lambda captured in nav args. The call site collects [decision] and reacts
 * whenever a non-null value arrives (`true` = retry with Tor, `false` = retry without Tor), then
 * calls [clear]. Not persisted: this is a single-retry signal, not state worth surviving process
 * death.
 */
interface PendingMigrationTorFailureDecisionRepository {
    val decision: StateFlow<Boolean?>

    fun set(useTor: Boolean)

    fun clear()
}

class PendingMigrationTorFailureDecisionRepositoryImpl : PendingMigrationTorFailureDecisionRepository {
    private val pending = MutableStateFlow<Boolean?>(null)

    override val decision: StateFlow<Boolean?> = pending

    override fun set(useTor: Boolean) {
        pending.value = useTor
    }

    override fun clear() {
        pending.value = null
    }
}
