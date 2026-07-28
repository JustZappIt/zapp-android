package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.preference.model.entry.StringPreferenceDefault
import java.time.Instant

/**
 * Per-account store for the consecutive "shift count" — how many times in a row
 * the migration lane has advanced for the same [transferId] while a completed sync
 * was observed since the previous shift.
 *
 * Stored as a single pipe-delimited string: `"transferId|count|epochSeconds"` under
 * key `"migration_shift_<accountKeyId>"`.
 *
 * Used by the two-lane scheduler to decide when to escalate from Automatic to IMMEDIATE.
 */
interface MigrationShiftCounterStorageProvider {
    /**
     * Returns the updated consecutive count for ([accountKeyId], [transferId]); resets when
     * [transferId] differs from the previously stored value.
     */
    suspend fun incrementIfSameTransfer(
        accountKeyId: String,
        transferId: String,
        syncCompletedSinceLastShift: Boolean,
    ): Int

    /** Clears the stored counter for [accountKeyId]. */
    suspend fun reset(accountKeyId: String)

    /** Returns the instant of the last shift for [accountKeyId], or `null` if none recorded. */
    suspend fun lastShiftAt(accountKeyId: String): Instant?
}

/**
 * Pure decision function for the shift counter.
 *
 * Rules (spec §2.B.4):
 * - Same [transferId] + [syncCompletedSinceLastShift] true  → [previousCount] + 1
 * - Same [transferId] + [syncCompletedSinceLastShift] false → [previousCount] (unchanged)
 * - Different [transferId] + [syncCompletedSinceLastShift] true  → 1
 * - Different [transferId] + [syncCompletedSinceLastShift] false → 0
 */
internal fun nextShiftCount(
    previousTransferId: String?,
    previousCount: Int,
    transferId: String,
    syncCompletedSinceLastShift: Boolean,
): Int =
    if (previousTransferId == transferId) {
        if (syncCompletedSinceLastShift) previousCount + 1 else previousCount
    } else {
        if (syncCompletedSinceLastShift) 1 else 0
    }

class MigrationShiftCounterStorageProviderImpl(
    private val preferenceHolder: StandardPreferenceProvider,
) : MigrationShiftCounterStorageProvider {
    override suspend fun incrementIfSameTransfer(
        accountKeyId: String,
        transferId: String,
        syncCompletedSinceLastShift: Boolean,
    ): Int {
        val pref = pref(accountKeyId)
        val stored = pref.getValue(preferenceHolder())
        val (prevId, prevCount) = parseStored(stored)
        val newCount = nextShiftCount(prevId, prevCount, transferId, syncCompletedSinceLastShift)
        val nowEpoch = Instant.now().epochSecond
        pref.putValue(preferenceHolder(), "$transferId|$newCount|$nowEpoch")
        return newCount
    }

    override suspend fun reset(accountKeyId: String) {
        pref(accountKeyId).clear(preferenceHolder())
    }

    override suspend fun lastShiftAt(accountKeyId: String): Instant? {
        val stored = pref(accountKeyId).getValue(preferenceHolder())
        if (stored.isEmpty()) return null
        val parts = stored.split("|")
        if (parts.size < 3) return null
        return parts[2].toLongOrNull()?.let { Instant.ofEpochSecond(it) }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun pref(accountKeyId: String) =
        StringPreferenceDefault(
            key = PreferenceKey("migration_shift_$accountKeyId"),
            defaultValue = "",
        )

    /**
     * Parses `"transferId|count|epochSeconds"` — returns `null` id and `0` count on malformed input.
     */
    private fun parseStored(stored: String): Pair<String?, Int> {
        if (stored.isEmpty()) return null to 0
        val parts = stored.split("|")
        if (parts.size < 2) return null to 0
        return parts[0] to (parts[1].toIntOrNull() ?: 0)
    }
}
