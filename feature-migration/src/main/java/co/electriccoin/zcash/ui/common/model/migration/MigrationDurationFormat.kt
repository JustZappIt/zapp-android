package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.ext.ZcashSdk
import co.electriccoin.zcash.migration.BuildConfig

/**
 * Formats a migration plan's total span so it reflects whatever interval the current
 * [cash.z.ecc.android.sdk.OrchardMigrationSdk] implementation actually schedules transfers at —
 * minutes for the compressed debug cadence, hours for the real one — instead of a hardcoded
 * "~24 hours" that only matched production timing.
 *
 * [fineGrained] (default: testnet builds) keeps minute resolution above one hour ("~1 h 15 min")
 * — the whole testnet plan spans ~1-2 h (12-block buckets), so integer-hour bucketing collapsed
 * most transfers into an identical "~1 hours" label. Mainnet keeps the coarse hour display.
 */
fun formatMigrationDuration(
    totalSeconds: Long,
    fineGrained: Boolean = isTestnetBuildFlavor(),
): String {
    val seconds = totalSeconds.coerceAtLeast(60L)
    val hours = seconds / 3600
    val minutesPastHour = (seconds % 3600) / 60
    return when {
        seconds < 3600 -> "~${seconds / 60} min"
        !fineGrained -> "~$hours hours"
        minutesPastHour == 0L -> "~$hours h"
        else -> "~$hours h $minutesPastHour min"
    }
}

internal fun isTestnetBuildFlavor(): Boolean = BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)

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
    // 75s protocol target as fallback. Pass OrchardMigrationSdk.estimatedSecondsPerBlock()
    // wherever an SDK is in reach — testnet's minimum-difficulty bursts make the constant a
    // large overestimate (observed live: "~1 h" plans coming due within minutes).
    secondsPerBlock: Long = ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000,
): Long = (toHeight - fromHeight) * secondsPerBlock
