package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MigrationAttentionTest {
    private fun transfer(
        index: Int,
        id: String,
        status: MigrationTransferStatus = MigrationTransferStatus.PENDING,
        expiryAtEpochSeconds: Long = 1_000L,
    ) = MigrationTransfer(
        index = index,
        amountZatoshi = 100_000L,
        scheduledAtEpochSeconds = 0L,
        status = status,
        expiryAtEpochSeconds = expiryAtEpochSeconds,
        id = id,
    )

    private fun plan(transfers: List<MigrationTransfer>) = MigrationPlan(
        id = "p1",
        createdAtEpochSeconds = 0L,
        transfers = transfers,
        mode = MigrationMode.AUTOMATIC,
    )

    @Test
    fun toUiKindMapsInvalidTransferToPlanUpdate() {
        assertEquals(MigrationAttentionKind.PLAN_UPDATE, AttentionReason.InvalidTransfer("t1").toUiKind())
    }

    @Test
    fun toUiKindMapsTransferExpiredToTransferExpired() {
        assertEquals(MigrationAttentionKind.TRANSFER_EXPIRED, AttentionReason.TransferExpired.toUiKind())
    }

    @Test
    fun invalidTransferFindsExactlyTheNamedTransferByIdNotIndex() {
        // Ids deliberately out of index order (ZIP 318 shuffles funding-note order away from
        // broadcast-height order) — this must still find "t1" at index 2, not index 1.
        val plan = plan(
            listOf(
                transfer(index = 0, id = "t0"),
                transfer(index = 1, id = "t2"),
                transfer(index = 2, id = "t1"),
            )
        )
        val indices = AttentionReason.InvalidTransfer("t1").affectedTransferIndices(plan, liveStates = null, nowEpochSeconds = 0L)
        assertEquals(listOf(2), indices)
    }

    @Test
    fun invalidTransferWithNoMatchingIdIsEmpty() {
        val plan = plan(listOf(transfer(index = 0, id = "t0")))
        val indices = AttentionReason.InvalidTransfer("unknown").affectedTransferIndices(plan, liveStates = null, nowEpochSeconds = 0L)
        assertEquals(emptyList(), indices)
    }

    @Test
    fun transferExpiredFindsEveryPendingTransferPastItsOwnExpiry() {
        val plan = plan(
            listOf(
                transfer(index = 0, id = "t0", status = MigrationTransferStatus.SENT, expiryAtEpochSeconds = 100L),
                transfer(index = 1, id = "t1", status = MigrationTransferStatus.PENDING, expiryAtEpochSeconds = 500L),
                transfer(index = 2, id = "t2", status = MigrationTransferStatus.PENDING, expiryAtEpochSeconds = 2_000L),
            )
        )
        // now=1000: t0 is SENT (excluded regardless of expiry), t1 is PENDING and past its expiry
        // (included), t2 is PENDING but not yet expired (excluded) — NOT "everything after the
        // last completed transfer" (the old, wrong behavior would have included both t1 and t2).
        val indices = AttentionReason.TransferExpired.affectedTransferIndices(plan, liveStates = null, nowEpochSeconds = 1_000L)
        assertEquals(listOf(1), indices)
    }

    @Test
    fun transferExpiredCorrelatesLiveSentStatusByIdBeforeCheckingExpiry() {
        // The cached plan still thinks index 1 is PENDING, but the live SDK state says it was
        // actually already sent — it must be excluded even though its cached expiry has passed.
        val plan = plan(
            listOf(
                transfer(index = 0, id = "t0", status = MigrationTransferStatus.PENDING, expiryAtEpochSeconds = 500L),
                transfer(index = 1, id = "t1", status = MigrationTransferStatus.PENDING, expiryAtEpochSeconds = 500L),
            )
        )
        val liveStates = MigrationTransferStates(
            transfers = listOf(
                MigrationTransferState(
                    id = "t1",
                    isTransfer = true,
                    isSent = true,
                    isProved = true,
                    scheduledHeight = 10L,
                    anchorBoundaryHeight = null,
                ),
            ),
            tipHeight = 10L,
        )
        val indices = AttentionReason.TransferExpired.affectedTransferIndices(plan, liveStates, nowEpochSeconds = 1_000L)
        assertEquals(listOf(0), indices)
    }

    @Test
    fun syncRequiredBeforeNextHasNoAffectedTransfers() {
        val plan = plan(listOf(transfer(index = 0, id = "t0")))
        val indices = AttentionReason.SyncRequiredBeforeNext.affectedTransferIndices(plan, liveStates = null, nowEpochSeconds = 0L)
        assertEquals(emptyList(), indices)
    }

    @Test
    fun rangeTextIsNullForEmptyIndices() {
        assertNull(emptyList<Int>().toMigrationRangeText())
    }

    @Test
    fun rangeTextIsASingleNumberForOneTransfer() {
        // affectedTransferIndices returns 0-based indices — the displayed "Transfer N" is 1-based.
        assertEquals("3", listOf(2).toMigrationRangeText())
    }

    @Test
    fun rangeTextIsAContiguousDashRangeForMultipleTransfers() {
        assertEquals("3–5", listOf(4, 2, 3).toMigrationRangeText())
    }
}
