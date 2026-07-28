package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.preference.model.entry.StringPreferenceDefault
import java.time.Instant

/**
 * Per-account store for the consecutive awaiting-proof STRIKE count — how many times in a row
 * Lane B found the same due [transferId] still unproven (the engine returned `AwaitingProof`)
 * while a completed sync was observed since the previous strike. "Shift" in the type/key names is
 * historical (the counter predates the deletion of the reschedule/shift stack); the storage keys
 * are deliberately unchanged so live installs keep their counts.
 *
 * Stored as a single pipe-delimited string: `"transferId|count|epochSeconds"` under
 * key `"migration_shift_<accountKeyId>"`.
 *
 * Used by the two-lane scheduler as the "sync ran but proving is still impossible" alarm — see
 * `shouldEscalateShift` in MigrationWorker.kt.
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
 * Parses a stored shift entry `"transferId|count|epochSeconds"`.
 * Handles transferIds containing pipes by taking the last two segments as count and epoch.
 * Returns triple of (transferId, count, epochSeconds); any malformed field returns null for that field.
 */
internal data class ParsedShiftEntry(
    val transferId: String?,
    val count: Int,
    val epochSeconds: Long?,
)

internal fun parseStoredShiftEntry(stored: String): ParsedShiftEntry {
    if (stored.isEmpty()) return ParsedShiftEntry(null, 0, null)
    val parts = stored.split("|")
    if (parts.size < 3) return ParsedShiftEntry(null, 0, null)

    val epochSeconds = parts.last().toLongOrNull()
    val count = parts[parts.size - 2].toIntOrNull() ?: 0
    val transferId = if (parts.size > 2) parts.dropLast(2).joinToString("|") else null

    return ParsedShiftEntry(transferId, count, epochSeconds)
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
        val parsed = parseStoredShiftEntry(stored)
        val newCount = nextShiftCount(parsed.transferId, parsed.count, transferId, syncCompletedSinceLastShift)
        val nowEpoch = Instant.now().epochSecond
        pref.putValue(preferenceHolder(), "$transferId|$newCount|$nowEpoch")
        return newCount
    }

    override suspend fun reset(accountKeyId: String) {
        pref(accountKeyId).clear(preferenceHolder())
    }

    override suspend fun lastShiftAt(accountKeyId: String): Instant? {
        val stored = pref(accountKeyId).getValue(preferenceHolder())
        val parsed = parseStoredShiftEntry(stored)
        return parsed.epochSeconds?.let { Instant.ofEpochSecond(it) }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun pref(accountKeyId: String) =
        StringPreferenceDefault(
            key = PreferenceKey("migration_shift_$accountKeyId"),
            defaultValue = "",
        )

}
