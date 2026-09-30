// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.stringRes
import org.junit.Test
import xyz.justzappit.offramp.atomicswap.ReversePhase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivateUsdReverseProgressTest {
    @Test
    fun approvalIsAnExplicitStepBeforeSettlement() {
        val steps = reverseSteps(ReversePhase.AWAITING_READY, 0)
        val active = steps.indexOfFirst { it.status == ZappStepStatus.InProgress }
        assertEquals(stringRes(R.string.reverse_ready), steps[active].label)
        assertTrue(steps.take(active).all { it.status == ZappStepStatus.Completed })
        assertTrue(steps.drop(active + 1).all { it.status == ZappStepStatus.Pending })
        assertEquals(listOf(stringRes(R.string.reverse_step_approval_detail)), steps[active].detailLines)
    }

    @Test
    fun minedSweepShowsConfirmationsUntilComplete() {
        val receiving = reverseSteps(ReversePhase.RECEIVING, 4)
        assertEquals(ZappStepStatus.InProgress, receiving.last().status)
        assertEquals(listOf(stringRes(R.string.reverse_sweep_confirmations, 4L)), receiving.last().detailLines)
        assertTrue(reverseSteps(ReversePhase.COMPLETE, 10).all { it.status == ZappStepStatus.Completed })
    }

    @Test
    fun refundAndCancellationDoNotShowSuccessfulSettlement() {
        for (phase in listOf(ReversePhase.REFUND_WAIT, ReversePhase.REFUNDING, ReversePhase.REFUND_PAYOUT)) {
            assertEquals(listOf(ZappStepStatus.InProgress), reverseSteps(phase, 0).map { it.status })
        }
        assertTrue(reverseSteps(ReversePhase.CANCELLED, 0).isEmpty())
        assertTrue(reverseSteps(ReversePhase.REFUNDED, 0).isEmpty())
    }
}
