// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GiftClaimOutcomeStageTest {
    @Test
    fun `funding that has not arrived stays distinct and retryable`() {
        assertEquals(
            GiftClaimStage.AWAITING_FUNDING,
            GiftClaimOutcome.AwaitingFunding.resultStage(),
        )
        assertEquals(R.string.gift_claim_subtitle_waiting, GiftClaimStage.AWAITING_FUNDING.subtitleRes())
    }

    @Test
    fun `a claim by another holder never uses the success stage or label`() {
        val stage = GiftClaimOutcome.AlreadyClaimed.resultStage()

        assertEquals(GiftClaimStage.ALREADY_CLAIMED, stage)
        assertNotEquals(GiftClaimStage.DONE, stage)
        assertEquals(R.string.gift_claim_subtitle_already_claimed, stage.subtitleRes())
        assertNotEquals(R.string.gift_claim_subtitle_done, stage.subtitleRes())
    }
}
