package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.ext.ZcashSdk

/**
 * Formats a migration plan's total span so it reflects whatever interval the current
 * [cash.z.ecc.android.sdk.OrchardMigrationSdk] implementation actually schedules transfers at —
 * minutes for the compressed debug cadence, hours for the real one — instead of a hardcoded
 * "~24 hours" that only matched production timing.
 */
fun formatMigrationDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(60L)
    return if (seconds < 3600) {
        "~${seconds / 60} min"
    } else {
        "~${seconds / 3600} hours"
    }
}

/**
 * Estimates the wall-clock duration, in seconds, spanned by a block-height difference, using the
 * network's average block time.
 *
 * [cash.z.ecc.android.sdk.TransferProposal]'s `anchorHeight`/`nextExecutableAfterHeight`/
 * `expiryHeight` are block heights, not timestamps — they must never be used directly as (or
 * compared against) epoch seconds. Doing so previously made every scheduled transfer appear
 * decades overdue: a block height (~4.18M) stored as `scheduledAtEpochSeconds` and later compared
 * against a real 2026 `Instant` measures the gap back to the Unix epoch, not to the actual
 * scheduled time.
 */
fun estimatedSecondsBetweenHeights(
    fromHeight: Long,
    toHeight: Long,
): Long = (toHeight - fromHeight) * (ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000)
