package co.electriccoin.zcash.ui.screen.migration.progress

import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for the progress screen's two engine-driven predicates:
 *  - [hasGenuinelyOverdueTransfer] (Issue 3a) — the graced, transfers-only button gate.
 *  - [hasBroadcastableTransfer]    (Issue 3b) — whether the foreground pass may broadcast.
 */
class MigrationProgressOverdueTest {
    private val grace = MigrationProgressVM.OVERDUE_GRACE_BLOCKS

    private fun transfer(
        id: Long = 1L,
        isTransfer: Boolean = true,
        isSent: Boolean = false,
        isProved: Boolean = true,
        scheduledHeight: Long = 1_000L,
    ) = MigrationTransferState(
        id = id,
        isTransfer = isTransfer,
        isSent = isSent,
        isProved = isProved,
        scheduledHeight = scheduledHeight,
        anchorBoundaryHeight = null,
    )

    private fun states(vararg t: MigrationTransferState, tipHeight: Long) =
        MigrationTransferStates(transfers = t.toList(), tipHeight = tipHeight)

    // ── hasGenuinelyOverdueTransfer (3a) ────────────────────────────────────────

    @Test
    fun overdue_is_false_when_states_are_null() {
        assertFalse(hasGenuinelyOverdueTransfer(null))
    }

    @Test
    fun overdue_is_false_right_at_scheduled_height_within_grace() {
        // Scheduled height reached but NOT yet past the grace window — normal execution, not a miss.
        val s = states(transfer(scheduledHeight = 1_000L), tipHeight = 1_000L)
        assertFalse(hasGenuinelyOverdueTransfer(s))
    }

    @Test
    fun overdue_is_false_one_block_short_of_the_grace_window() {
        val s = states(transfer(scheduledHeight = 1_000L), tipHeight = 1_000L + grace - 1)
        assertFalse(hasGenuinelyOverdueTransfer(s))
    }

    @Test
    fun overdue_is_true_once_past_the_grace_window() {
        val s = states(transfer(scheduledHeight = 1_000L), tipHeight = 1_000L + grace)
        assertTrue(hasGenuinelyOverdueTransfer(s))
    }

    @Test
    fun overdue_excludes_preparations_even_when_far_past_grace() {
        // A preparation past its scheduled height must NOT light the recovery buttons.
        val s =
            states(
                transfer(isTransfer = false, scheduledHeight = 1_000L),
                tipHeight = 1_000L + grace + 100L,
            )
        assertFalse(hasGenuinelyOverdueTransfer(s))
    }

    @Test
    fun overdue_excludes_already_sent_transfers() {
        val s =
            states(
                transfer(isSent = true, scheduledHeight = 1_000L),
                tipHeight = 1_000L + grace + 50L,
            )
        assertFalse(hasGenuinelyOverdueTransfer(s))
    }

    @Test
    fun overdue_is_true_when_any_transfer_is_genuinely_overdue() {
        val s =
            states(
                transfer(id = 1L, isSent = true, scheduledHeight = 900L),
                transfer(id = 2L, scheduledHeight = 1_000L),
                tipHeight = 1_000L + grace,
            )
        assertTrue(hasGenuinelyOverdueTransfer(s))
    }

    @Test
    fun overdue_requires_proved_unproved_past_grace_is_not_overdue() {
        // An unproved transfer past the grace window is NOT genuinely overdue — it is simply
        // waiting for Lane A's sync to produce a proof. Showing "Send now" would be a false alarm
        // because the user can't manually broadcast an unproved transfer.
        val s = states(transfer(isProved = false, scheduledHeight = 1_000L), tipHeight = 1_000L + grace)
        assertFalse(hasGenuinelyOverdueTransfer(s))
    }

    @Test
    fun overdue_is_true_for_proved_transfer_past_grace_window() {
        val s = states(transfer(isProved = true, scheduledHeight = 1_000L), tipHeight = 1_000L + grace)
        assertTrue(hasGenuinelyOverdueTransfer(s))
    }

    // ── hasBroadcastableTransfer (3b) ───────────────────────────────────────────

    @Test
    fun broadcastable_is_false_when_states_are_null() {
        assertFalse(hasBroadcastableTransfer(null))
    }

    @Test
    fun broadcastable_requires_a_proof() {
        val s = states(transfer(isProved = false, scheduledHeight = 1_000L), tipHeight = 1_000L)
        assertFalse(hasBroadcastableTransfer(s))
    }

    @Test
    fun broadcastable_requires_scheduled_height_reached() {
        val s = states(transfer(isProved = true, scheduledHeight = 1_001L), tipHeight = 1_000L)
        assertFalse(hasBroadcastableTransfer(s))
    }

    @Test
    fun broadcastable_true_for_a_proved_due_unsent_transfer() {
        val s = states(transfer(isProved = true, scheduledHeight = 1_000L), tipHeight = 1_000L)
        assertTrue(hasBroadcastableTransfer(s))
    }

    @Test
    fun broadcastable_true_for_a_proved_due_preparation_kind_agnostic() {
        // Unlike the button gate, the broadcast pass is kind-agnostic: it must not sleep past a
        // due preparation layer (the engine serves those for broadcast exactly like transfers).
        val s = states(transfer(isTransfer = false, isProved = true, scheduledHeight = 1_000L), tipHeight = 1_000L)
        assertTrue(hasBroadcastableTransfer(s))
    }

    @Test
    fun broadcastable_excludes_already_sent() {
        val s = states(transfer(isProved = true, isSent = true, scheduledHeight = 1_000L), tipHeight = 1_000L)
        assertFalse(hasBroadcastableTransfer(s))
    }
}
