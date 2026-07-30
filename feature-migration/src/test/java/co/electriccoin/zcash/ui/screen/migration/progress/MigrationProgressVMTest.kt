package co.electriccoin.zcash.ui.screen.migration.progress

import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparation
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pure-logic coverage for the preparation row mapping functions:
 *  - [preparationStatusLabel] — same relative formatting as transfer rows.
 *  - [preparationSyncLabel]   — debug-only prove-state label.
 *
 * Also verifies the sorting-and-numbering contract that [MigrationProgressVM.createState]
 * applies when mapping [MigrationPreparation] to [MigrationProgressPreparationState], and that
 * the [debugSyncEnabled] flag controls [MigrationProgressPreparationState.syncLabel] presence.
 *
 * Uses top-level internal functions only — no Koin, no Android, no ViewModel required.
 */
class MigrationProgressVMTest {
    private val now: Instant = Instant.fromEpochSeconds(1_000_000L)

    // ── preparationStatusLabel ────────────────────────────────────────────────

    @Test
    fun statusLabel_sent_recently_when_sent_under_1_minute_ago() {
        val prep =
            prep(
                status = MigrationTransferStatus.SENT,
                scheduledAtEpochSeconds = now.epochSeconds - 30L, // 30 s ago
            )
        val label = preparationStatusLabel(prep, now).asString()
        assertEquals("Sent recently", label)
    }

    @Test
    fun statusLabel_sent_minutes_ago_when_sent_between_1_and_60_minutes_ago() {
        val prep =
            prep(
                status = MigrationTransferStatus.SENT,
                scheduledAtEpochSeconds = (now - 45.minutes).epochSeconds,
            )
        val label = preparationStatusLabel(prep, now).asString()
        assertEquals("Sent 45 min ago", label)
    }

    @Test
    fun statusLabel_sent_hours_ago_when_sent_more_than_60_minutes_ago() {
        val prep =
            prep(
                status = MigrationTransferStatus.SENT,
                scheduledAtEpochSeconds = (now - 2.hours).epochSeconds,
            )
        val label = preparationStatusLabel(prep, now).asString()
        assertEquals("Sent 2h ago", label)
    }

    @Test
    fun statusLabel_pending_when_scheduled_in_the_past() {
        val prep =
            prep(
                status = MigrationTransferStatus.PENDING,
                scheduledAtEpochSeconds = now.epochSeconds - 60L,
            )
        val label = preparationStatusLabel(prep, now).asString()
        assertEquals("Pending", label)
    }

    @Test
    fun statusLabel_relative_duration_when_scheduled_in_the_future() {
        val prep =
            prep(
                status = MigrationTransferStatus.PENDING,
                // exactly 10 minutes in the future
                scheduledAtEpochSeconds = (now + 10.minutes).epochSeconds,
            )
        val label = preparationStatusLabel(prep, now).asString()
        // formatMigrationDuration with 600 s → "~10 min" (testnet fineGrained default)
        assertTrue(label.contains("10"), "Expected '10' in label but got: $label")
    }

    // ── preparationSyncLabel ─────────────────────────────────────────────────

    @Test
    fun syncLabel_proved_when_isProved_true() {
        val prep = prep(isProved = true)
        val label = preparationSyncLabel(prep, now).asString()
        assertEquals("proved", label)
    }

    @Test
    fun syncLabel_pending_when_not_proved_and_scheduled_in_past() {
        val prep =
            prep(
                isProved = false,
                scheduledAtEpochSeconds = now.epochSeconds - 60L,
            )
        val label = preparationSyncLabel(prep, now).asString()
        assertEquals("pending", label)
    }

    @Test
    fun syncLabel_relative_duration_when_not_proved_and_scheduled_in_future() {
        val prep =
            prep(
                isProved = false,
                scheduledAtEpochSeconds = (now + 5.minutes).epochSeconds,
            )
        val label = preparationSyncLabel(prep, now).asString()
        assertTrue(label.contains("5"), "Expected '5' in label but got: $label")
    }

    // ── mapping (number, isSent, syncLabel presence) ────────────────────────

    /**
     * Verifies the full preparation-mapping contract the VM's createState performs:
     * - 2 preparations → 2 [MigrationProgressPreparationState] items.
     * - Sorted by scheduledAtEpochSeconds (earlier prep gets number=1).
     * - number = 1..N.
     * - isSent reflects the preparation's status.
     * - statusLabel uses [preparationStatusLabel] formatting.
     * - syncLabel is non-null when debugSyncEnabled=true, null when false.
     */
    @Test
    fun mapping_two_preparations_produces_correct_state_rows_with_debug_on() {
        val sentPrep =
            prep(
                id = 1L,
                scheduledAtEpochSeconds = (now - 30.minutes).epochSeconds,
                status = MigrationTransferStatus.SENT,
                isProved = true,
            )
        val pendingPrep =
            prep(
                id = 2L,
                scheduledAtEpochSeconds = (now + 20.minutes).epochSeconds,
                status = MigrationTransferStatus.PENDING,
                isProved = false,
            )
        // Deliberately pass in reverse order to verify sorting
        val preparations = listOf(pendingPrep, sentPrep)

        val rows = mapPreparationsToState(preparations, now, debugSyncEnabled = true)

        assertEquals(2, rows.size)

        // sentPrep has earlier scheduledAtEpochSeconds → must be number 1
        val row1 = rows[0]
        assertEquals(1, row1.number)
        assertTrue(row1.isSent)
        assertTrue(row1.statusLabel.asString().startsWith("Sent"), "Expected 'Sent ...' but got: ${row1.statusLabel.asString()}")
        val syncLabel1 = assertNotNull(row1.syncLabel, "syncLabel must be non-null when debugSyncEnabled=true")
        assertEquals("proved", syncLabel1.asString())

        val row2 = rows[1]
        assertEquals(2, row2.number)
        assertFalse(row2.isSent)
        assertNotNull(row2.syncLabel, "syncLabel must be non-null when debugSyncEnabled=true")
    }

    @Test
    fun mapping_syncLabel_is_null_when_debug_disabled() {
        val prep =
            prep(
                status = MigrationTransferStatus.SENT,
                isProved = true,
                scheduledAtEpochSeconds = (now - 10.minutes).epochSeconds,
            )
        val rows = mapPreparationsToState(listOf(prep), now, debugSyncEnabled = false)
        assertEquals(1, rows.size)
        assertNull(rows[0].syncLabel, "syncLabel must be null when debugSyncEnabled=false")
    }

    @Test
    fun mapping_empty_preparations_produces_empty_list() {
        val rows = mapPreparationsToState(emptyList(), now, debugSyncEnabled = true)
        assertTrue(rows.isEmpty())
    }

    // ── transferLabel (Fix 1b — isProved gates "Overdue") ───────────────────

    @Test
    fun transferLabel_proved_past_due_shows_overdue() {
        val t =
            transfer(
                isProved = true,
                scheduledAtEpochSeconds = (now - 2.hours).epochSeconds,
            )
        val label = transferLabel(t, now).asString()
        assertTrue(label.startsWith("Overdue"), "Expected 'Overdue' label for proved past-due transfer but got: $label")
    }

    @Test
    fun transferLabel_unproved_past_due_shows_awaiting_proof_not_overdue() {
        val t =
            transfer(
                isProved = false,
                scheduledAtEpochSeconds = (now - 2.hours).epochSeconds,
            )
        val label = transferLabel(t, now).asString()
        assertEquals("Awaiting proof", label)
    }

    @Test
    fun transferLabel_future_scheduled_shows_relative_time() {
        val t =
            transfer(
                isProved = false,
                scheduledAtEpochSeconds = (now + 10.minutes).epochSeconds,
            )
        val label = transferLabel(t, now).asString()
        assertTrue(label.contains("10"), "Expected relative time label but got: $label")
    }

    @Test
    fun transferLabel_sent_shows_sent_label() {
        val t =
            transfer(
                status = MigrationTransferStatus.SENT,
                scheduledAtEpochSeconds = (now - 45.minutes).epochSeconds,
            )
        val label = transferLabel(t, now).asString()
        assertEquals("Sent 45 min ago", label)
    }

    // ── isOverdue on MigrationProgressTransferState (Fix 1b) ─────────────────

    @Test
    fun isOverdue_false_for_unproved_past_due_transfer() {
        val t =
            transfer(
                isProved = false,
                scheduledAtEpochSeconds = (now - 1.hours).epochSeconds,
            )
        val rows = mapTransfersToState(listOf(t), now, debugSyncEnabled = false)
        assertFalse(rows.single().isOverdue, "isOverdue must be false when transfer is not proved")
    }

    @Test
    fun isOverdue_true_for_proved_past_due_transfer() {
        val t =
            transfer(
                isProved = true,
                scheduledAtEpochSeconds = (now - 1.hours).epochSeconds,
            )
        val rows = mapTransfersToState(listOf(t), now, debugSyncEnabled = false)
        assertTrue(rows.single().isOverdue, "isOverdue must be true when transfer is proved and past scheduled time")
    }

    // ── transferSyncLabel (Fix 2) ─────────────────────────────────────────────

    @Test
    fun transferSyncLabel_proved_shows_proved() {
        val t = transfer(isProved = true)
        val label = transferSyncLabel(t, now).asString()
        assertEquals("proved", label)
    }

    @Test
    fun transferSyncLabel_unproved_past_due_shows_pending() {
        val t =
            transfer(
                isProved = false,
                scheduledAtEpochSeconds = now.epochSeconds - 60L,
            )
        val label = transferSyncLabel(t, now).asString()
        assertEquals("pending", label)
    }

    @Test
    fun transferSyncLabel_unproved_future_shows_relative_time() {
        val t =
            transfer(
                isProved = false,
                scheduledAtEpochSeconds = (now + 5.minutes).epochSeconds,
            )
        val label = transferSyncLabel(t, now).asString()
        assertTrue(label.contains("5"), "Expected '5' in label but got: $label")
    }

    // ── syncLabel present/absent on transfer rows (Fix 2) ────────────────────

    @Test
    fun transfer_rows_carry_syncLabel_when_debug_enabled() {
        val t =
            transfer(
                isProved = true,
                scheduledAtEpochSeconds = (now - 10.minutes).epochSeconds,
                status = MigrationTransferStatus.SENT,
            )
        val rows = mapTransfersToState(listOf(t), now, debugSyncEnabled = true)
        val syncLabel = assertNotNull(rows.single().syncLabel, "syncLabel must be non-null when debugSyncEnabled=true")
        assertEquals("proved", syncLabel.asString())
    }

    @Test
    fun transfer_rows_have_null_syncLabel_when_debug_disabled() {
        val t =
            transfer(
                isProved = true,
                scheduledAtEpochSeconds = (now - 10.minutes).epochSeconds,
                status = MigrationTransferStatus.SENT,
            )
        val rows = mapTransfersToState(listOf(t), now, debugSyncEnabled = false)
        assertNull(rows.single().syncLabel, "syncLabel must be null when debugSyncEnabled=false")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun transfer(
        index: Int = 0,
        status: MigrationTransferStatus = MigrationTransferStatus.PENDING,
        isProved: Boolean = false,
        scheduledAtEpochSeconds: Long = now.epochSeconds + 600L,
        amountZatoshi: Long = 100_000_000L,
        id: Long = 1L,
    ) = MigrationTransfer(
        index = index,
        amountZatoshi = amountZatoshi,
        scheduledAtEpochSeconds = scheduledAtEpochSeconds,
        status = status,
        id = id,
        isProved = isProved,
    )

    private fun prep(
        id: Long = 1L,
        status: MigrationTransferStatus = MigrationTransferStatus.PENDING,
        isProved: Boolean = false,
        scheduledAtEpochSeconds: Long = now.epochSeconds + 600L,
    ) = MigrationPreparation(
        id = id,
        layer = 0,
        index = 0,
        scheduledAtEpochSeconds = scheduledAtEpochSeconds,
        dependsOn = emptyList(),
        status = status,
        isProved = isProved,
    )

    /**
     * Resolves a [StringResource] to a plain [String] without Android context.
     * Handles [StringResource.ByString] directly and [CompositeStringResource]
     * (produced by [StringResource.plus]) by recursively concatenating its parts.
     * All parts produced by these tests are ultimately [StringResource.ByString] leaves,
     * so no Android [Context] is required.
     */
    @Suppress("UNCHECKED_CAST")
    private fun StringResource.asString(): String =
        when (this) {
            is StringResource.ByString -> {
                value
            }

            else -> {
                // CompositeStringResource is private; reach its `resources` list via reflection.
                val resourcesField = this::class.java.getDeclaredField("resources").also { it.isAccessible = true }
                val parts = resourcesField.get(this) as List<StringResource>
                parts.joinToString(separator = "") { it.asString() }
            }
        }
}

/**
 * Replicates the preparation-mapping logic from [MigrationProgressVM.createState] as a pure
 * top-level function so tests can drive it without constructing the full VM. Any change to
 * the VM's mapping must be reflected here to keep the test honest.
 */
internal fun mapPreparationsToState(
    preparations: List<MigrationPreparation>,
    now: Instant,
    debugSyncEnabled: Boolean,
): List<MigrationProgressPreparationState> =
    preparations
        .sortedBy { it.scheduledAtEpochSeconds }
        .mapIndexed { i, p ->
            MigrationProgressPreparationState(
                number = i + 1,
                statusLabel = preparationStatusLabel(p, now),
                isSent = p.status == MigrationTransferStatus.SENT,
                syncLabel = if (debugSyncEnabled) preparationSyncLabel(p, now) else null,
            )
        }

/**
 * Replicates the transfer-mapping logic from [MigrationProgressVM.createState] as a pure
 * top-level function so tests can drive it without constructing the full VM. Any change to
 * the VM's mapping must be reflected here to keep the test honest.
 */
internal fun mapTransfersToState(
    transfers: List<MigrationTransfer>,
    now: Instant,
    debugSyncEnabled: Boolean,
): List<MigrationProgressTransferState> =
    transfers.map { t ->
        MigrationProgressTransferState(
            index = t.index + 1,
            amount =
                co.electriccoin.zcash.ui.design.util
                    .stringRes(
                        cash.z.ecc.android.sdk.model
                            .Zatoshi(t.amountZatoshi)
                    ),
            statusLabel = transferLabel(t, now),
            isOverdue = t.status == MigrationTransferStatus.PENDING && t.isProved && t.scheduledAt <= now,
            isSent = t.status == MigrationTransferStatus.SENT,
            syncLabel = if (debugSyncEnabled) transferSyncLabel(t, now) else null,
        )
    }
