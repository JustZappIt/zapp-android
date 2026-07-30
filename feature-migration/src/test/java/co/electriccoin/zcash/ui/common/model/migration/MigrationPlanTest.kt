package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.PreparationStep
import cash.z.ecc.android.sdk.TransferProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationPlanTest {
    @Test
    fun `withLiveState populates isProved on crossing transfers from live state`() {
        val schedule =
            MigrationSchedule(
                transfers =
                    listOf(
                        TransferProposal(
                            id = 7,
                            amountZatoshi = 200000000,
                            anchorHeight = 100,
                            nextExecutableAfterHeight = 110,
                            expiryHeight = 9999
                        ),
                        TransferProposal(
                            id = 8,
                            amountZatoshi = 300000000,
                            anchorHeight = 100,
                            nextExecutableAfterHeight = 150,
                            expiryHeight = 9999
                        ),
                    ),
                preparations = emptyList(),
                estimatedDurationHours = 1,
                proposalHandle = 1,
            )
        val plan = schedule.toMigrationPlan(mode = MigrationMode.AUTOMATIC, secondsPerBlock = 28)

        val live =
            MigrationTransferStates(
                transfers =
                    listOf(
                        MigrationTransferState(
                            id = 7,
                            isTransfer = true,
                            isSent = false,
                            isProved = true,
                            scheduledHeight = 110,
                            anchorBoundaryHeight = 100
                        ),
                        MigrationTransferState(
                            id = 8,
                            isTransfer = true,
                            isSent = false,
                            isProved = false,
                            scheduledHeight = 150,
                            anchorBoundaryHeight = 100
                        ),
                    ),
                tipHeight = 115,
            )
        val overlaid = plan.withLiveState(live, secondsPerBlock = 28)

        val byId = overlaid.transfers.associateBy { it.id }
        assertTrue(byId.getValue(7L).isProved, "Transfer 7 (proved live) must have isProved=true after overlay")
        assertFalse(byId.getValue(8L).isProved, "Transfer 8 (unproved live) must have isProved=false after overlay")
    }

    @Test
    fun `toMigrationPlan carries preparations and withLiveState marks them proved and sent`() {
        val schedule =
            MigrationSchedule(
                transfers =
                    listOf(
                        TransferProposal(
                            id = 4,
                            amountZatoshi = 500000000,
                            anchorHeight = 100,
                            nextExecutableAfterHeight = 208,
                            expiryHeight = 9999
                        )
                    ),
                preparations = listOf(PreparationStep(id = 0, layer = 0, index = 0, broadcastHeight = 137, dependsOn = emptyList())),
                estimatedDurationHours = 1,
                proposalHandle = 1,
            )
        val plan = schedule.toMigrationPlan(mode = MigrationMode.AUTOMATIC, secondsPerBlock = 28)
        assertEquals(1, plan.preparations.size)
        assertEquals(0L, plan.preparations.first().id)
        assertEquals(MigrationTransferStatus.PENDING, plan.preparations.first().status)

        val live =
            MigrationTransferStates(
                transfers =
                    listOf(
                        MigrationTransferState(
                            id = 0,
                            isTransfer = false,
                            isSent = true,
                            isProved = true,
                            scheduledHeight = 137,
                            anchorBoundaryHeight = null
                        )
                    ),
                tipHeight = 200,
            )
        val overlaid = plan.withLiveState(live, secondsPerBlock = 28)
        assertEquals(MigrationTransferStatus.SENT, overlaid.preparations.first().status)
        assertTrue(overlaid.preparations.first().isProved)

        // scheduledAtEpochSeconds must be recomputed from the live tip→scheduledHeight delta —
        // including the negative case (scheduledHeight=137 < tipHeight=200 → delta = -63 blocks).
        // The propose-time value used baseline=100→scheduledHeight=137 (+37 blocks, positive).
        // The overlaid value must differ: now + (137-200)*28  ≠  now + (137-100)*28.
        val proposeTimeScheduled = plan.preparations.first().scheduledAtEpochSeconds
        val overlaidScheduled = overlaid.preparations.first().scheduledAtEpochSeconds
        // The delta from the live overlay: (scheduledHeight - tipHeight) * secondsPerBlock = (137-200)*28 = -1764
        // The delta from propose time:     (broadcastHeight - baseline) * secondsPerBlock  = (137-100)*28 = +1036
        // They differ by 2800 seconds, so the overlaid value must be strictly less.
        assertTrue(
            overlaidScheduled < proposeTimeScheduled,
            "overlaid scheduledAtEpochSeconds ($overlaidScheduled) should be less than propose-time value ($proposeTimeScheduled) " +
                "because the live tip (200) is past scheduledHeight (137), yielding a negative block delta"
        )
    }
}
