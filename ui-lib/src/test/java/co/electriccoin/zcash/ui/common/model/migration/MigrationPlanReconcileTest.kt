package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationPlanReconcileTest {
    private fun transfer(
        index: Int,
        id: String,
        status: MigrationTransferStatus = MigrationTransferStatus.PENDING,
        scheduledAtEpochSeconds: Long = 1_234L,
    ) = MigrationTransfer(
        index = index,
        amountZatoshi = 100_000L,
        scheduledAtEpochSeconds = scheduledAtEpochSeconds,
        status = status,
        expiryAtEpochSeconds = 9_000L,
        id = id,
    )

    private fun plan(transfers: List<MigrationTransfer>) = MigrationPlan(
        id = "p1",
        createdAtEpochSeconds = 0L,
        transfers = transfers,
        mode = MigrationMode.AUTOMATIC,
    )

    private fun live(vararg sent: Pair<String, Boolean>, tipHeight: Long = 100L) =
        MigrationTransferStates(
            transfers = sent.map { (id, isSent) ->
                MigrationTransferState(
                    id = id,
                    isTransfer = true,
                    isSent = isSent,
                    isProved = isSent,
                    scheduledHeight = 50L,
                    anchorBoundaryHeight = null,
                )
            },
            tipHeight = tipHeight,
        )

    @Test
    fun marksMatchingPendingTransferSentByIdNotIndex() {
        // Ids out of index order (ZIP 318 shuffle): the live-sent "t1" is at index 2, not index 1.
        val plan = plan(
            listOf(
                transfer(index = 0, id = "t0"),
                transfer(index = 1, id = "t2"),
                transfer(index = 2, id = "t1"),
            )
        )
        val result = plan.withLiveStatusOnly(live("t1" to true))
        assertEquals(MigrationTransferStatus.PENDING, result.transfers[1].status)
        assertEquals(MigrationTransferStatus.SENT, result.transfers[2].status)
        assertEquals(1, result.completedCount)
    }

    @Test
    fun preservesScheduledAtUnlikeWithLiveState() {
        val plan = plan(listOf(transfer(index = 0, id = "t0", scheduledAtEpochSeconds = 5_555L)))
        val result = plan.withLiveStatusOnly(live("t0" to true, tipHeight = 999L))
        // Only status changes — the cached schedule is left exactly as-is (no tip-based re-estimate).
        assertEquals(5_555L, result.transfers[0].scheduledAtEpochSeconds)
        assertEquals(MigrationTransferStatus.SENT, result.transfers[0].status)
    }

    @Test
    fun leavesTransfersWithNoLiveMatchUntouched() {
        val plan = plan(
            listOf(
                transfer(index = 0, id = "t0"),
                transfer(index = 1, id = "t1"),
            )
        )
        val result = plan.withLiveStatusOnly(live("t0" to true))
        assertEquals(MigrationTransferStatus.SENT, result.transfers[0].status)
        assertEquals(MigrationTransferStatus.PENDING, result.transfers[1].status)
    }

    @Test
    fun onlyUpgradesNeverDowngradesAlreadySentTransfer() {
        val plan = plan(
            listOf(
                transfer(index = 0, id = "t0", status = MigrationTransferStatus.SENT),
                transfer(index = 1, id = "t1"),
            )
        )
        // Live reports t0 not-sent (never actually happens, but must not clobber a cache-advanced SENT).
        val result = plan.withLiveStatusOnly(live("t0" to false, "t1" to true))
        assertEquals(MigrationTransferStatus.SENT, result.transfers[0].status)
        assertEquals(MigrationTransferStatus.SENT, result.transfers[1].status)
    }

    @Test
    fun nullLiveReturnsPlanUnchanged() {
        val plan = plan(listOf(transfer(index = 0, id = "t0")))
        assertEquals(plan, plan.withLiveStatusOnly(null))
    }

    @Test
    fun advancesIsCompleteWhenLiveReportsAllSent() {
        val plan = plan(
            listOf(
                transfer(index = 0, id = "t0"),
                transfer(index = 1, id = "t1"),
            )
        )
        val result = plan.withLiveStatusOnly(live("t0" to true, "t1" to true))
        assertEquals(true, result.isComplete)
        assertEquals(null, result.nextPending)
    }
}
