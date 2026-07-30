package co.electriccoin.zcash.ui.screen.migration.keystonesign

import kotlin.test.Test
import kotlin.test.assertEquals

class KeystoneBatchChunkingTest {
    // Regression coverage for the batch-size-cap gap found comparing against Vizor's reference
    // implementation (docs/superpowers/specs/2026-07-19-vizor-migration-reference-comparison.md):
    // MIGRATION_MAX_PREPARED_NOTES_PER_RUN=64 means a real migration can need up to 65 Keystone
    // signing items (1 split + 64 transfers), well over any device-safe QR batch size.

    @Test
    fun totalRounds_is_zero_when_there_is_nothing_to_sign() {
        assertEquals(0, keystoneBatchTotalRounds(hasSplit = false, transferCount = 0, maxItems = 35))
    }

    @Test
    fun totalRounds_is_one_when_everything_fits_under_the_cap() {
        assertEquals(1, keystoneBatchTotalRounds(hasSplit = true, transferCount = 12, maxItems = 35))
        assertEquals(1, keystoneBatchTotalRounds(hasSplit = false, transferCount = 35, maxItems = 35))
    }

    @Test
    fun totalRounds_is_one_when_exactly_at_the_cap_with_a_split() {
        // split + 34 transfers = 35 items, exactly the cap.
        assertEquals(1, keystoneBatchTotalRounds(hasSplit = true, transferCount = 34, maxItems = 35))
    }

    @Test
    fun totalRounds_is_two_one_item_over_the_cap() {
        assertEquals(2, keystoneBatchTotalRounds(hasSplit = false, transferCount = 36, maxItems = 35))
        // split + 35 transfers = 36 items, one over the cap.
        assertEquals(2, keystoneBatchTotalRounds(hasSplit = true, transferCount = 35, maxItems = 35))
    }

    @Test
    fun totalRounds_covers_the_real_worst_case_of_one_split_and_64_transfers() {
        assertEquals(2, keystoneBatchTotalRounds(hasSplit = true, transferCount = 64, maxItems = 35))
    }

    @Test
    fun roundSlice_single_round_includes_the_split_and_every_transfer() {
        val slice = keystoneBatchRoundSlice(roundIndex = 0, hasSplit = true, transferCount = 12, maxItems = 35)
        assertEquals(KeystoneBatchRoundSlice(includeSplit = true, transferRange = 0 until 12), slice)
    }

    @Test
    fun roundSlice_splits_the_worst_case_into_two_non_overlapping_exhaustive_rounds() {
        val round0 = keystoneBatchRoundSlice(roundIndex = 0, hasSplit = true, transferCount = 64, maxItems = 35)
        val round1 = keystoneBatchRoundSlice(roundIndex = 1, hasSplit = true, transferCount = 64, maxItems = 35)

        // Round 0: split + 34 transfers = 35 items, exactly the cap.
        assertEquals(KeystoneBatchRoundSlice(includeSplit = true, transferRange = 0 until 34), round0)
        // Round 1: no split (already sent), remaining 30 transfers.
        assertEquals(KeystoneBatchRoundSlice(includeSplit = false, transferRange = 34 until 64), round1)

        // Every transfer is covered exactly once across all rounds.
        val covered = (round0.transferRange + round1.transferRange).toSet()
        assertEquals((0 until 64).toSet(), covered)
    }

    @Test
    fun roundSlice_without_a_split_packs_transfers_tightly_at_the_boundary() {
        val round0 = keystoneBatchRoundSlice(roundIndex = 0, hasSplit = false, transferCount = 36, maxItems = 35)
        val round1 = keystoneBatchRoundSlice(roundIndex = 1, hasSplit = false, transferCount = 36, maxItems = 35)

        assertEquals(KeystoneBatchRoundSlice(includeSplit = false, transferRange = 0 until 35), round0)
        assertEquals(KeystoneBatchRoundSlice(includeSplit = false, transferRange = 35 until 36), round1)
    }
}

private operator fun IntRange.plus(other: IntRange): List<Int> = this.toList() + other.toList()
