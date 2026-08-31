// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class IncreaseReputationProgressTest {
    @Test
    fun `ready session stays pending until the user starts verification`() {
        val steps = verificationSteps(VerificationStage.READY, VerificationStage.READY)

        assertEquals(
            listOf(ZappStepStatus.Pending, ZappStepStatus.Pending, ZappStepStatus.Pending),
            steps.map { it.status },
        )
    }

    @Test
    fun `verification starts progress after the user launches Reclaim`() {
        val steps = verificationSteps(VerificationStage.VERIFYING, VerificationStage.VERIFYING)

        assertEquals(
            listOf(ZappStepStatus.Completed, ZappStepStatus.InProgress, ZappStepStatus.Pending),
            steps.map { it.status },
        )
    }
}
