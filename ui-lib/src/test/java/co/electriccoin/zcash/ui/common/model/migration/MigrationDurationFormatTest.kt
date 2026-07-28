package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.ext.ZcashSdk
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationDurationFormatTest {
    // Regression coverage for a real bug: TransferProposal's anchorHeight/nextExecutableAfterHeight
    // are block heights, not epoch seconds. Using them directly as (or against) a timestamp made
    // every scheduled transfer appear ~56 years overdue on a live device, since a raw block height
    // (~4.18M) measured back to the Unix epoch is decades in the past relative to any real 2026
    // Instant.

    @Test
    fun estimatedSecondsBetweenHeights_converts_block_delta_using_the_network_block_time() {
        val blocksApart = 288L // one ZIP-318-style transfer-scheduling step
        val expectedSeconds = blocksApart * (ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000)

        val actual = estimatedSecondsBetweenHeights(fromHeight = 4_180_000L, toHeight = 4_180_000L + blocksApart)

        assertEquals(expectedSeconds, actual)
    }

    @Test
    fun estimatedSecondsBetweenHeights_is_zero_for_equal_heights() {
        assertEquals(0L, estimatedSecondsBetweenHeights(fromHeight = 4_180_000L, toHeight = 4_180_000L))
    }

    @Test
    fun estimatedSecondsBetweenHeights_is_negative_when_the_target_height_is_in_the_past() {
        // A transfer's own anchorHeight can be behind the height it's compared against (e.g. an
        // overdue transfer's nextExecutableAfterHeight is now below the current tip) — the caller
        // is responsible for clamping to zero/"overdue", this function must not do so itself.
        val actual = estimatedSecondsBetweenHeights(fromHeight = 4_180_100L, toHeight = 4_180_000L)

        assertEquals(-100L * (ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000), actual)
    }

    @Test
    fun estimatedSecondsBetweenHeights_never_conflates_a_bare_height_with_epoch_seconds() {
        // The historical bug this guards against: passing a raw height straight through as if it
        // were already a duration/timestamp. A single real-world height (millions) must not survive
        // unconverted — it must always be scaled by the block interval.
        val height = 4_180_824L

        val secondsFromGenesis = estimatedSecondsBetweenHeights(fromHeight = 0L, toHeight = height)

        assertEquals(height * (ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000), secondsFromGenesis)
        assertEquals(height * 75L, secondsFromGenesis)
    }

    @Test
    fun formatMigrationDuration_keeps_minute_resolution_above_an_hour_when_fine_grained() {
        assertEquals("~1 h 15 min", formatMigrationDuration(totalSeconds = 4_500L, fineGrained = true))
        assertEquals("~2 h", formatMigrationDuration(totalSeconds = 7_200L, fineGrained = true))
    }

    @Test
    fun formatMigrationDuration_uses_coarse_hours_when_not_fine_grained() {
        assertEquals("~1 hours", formatMigrationDuration(totalSeconds = 4_500L, fineGrained = false))
    }

    @Test
    fun formatMigrationDuration_shows_minutes_below_an_hour_in_both_modes() {
        assertEquals("~15 min", formatMigrationDuration(totalSeconds = 900L, fineGrained = true))
        assertEquals("~15 min", formatMigrationDuration(totalSeconds = 900L, fineGrained = false))
    }
}
