// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.offramp.orchestrator.OfframpCheckpoint
import xyz.justzappit.offramp.orchestrator.OfframpStep
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleOfframpCommitmentTest {
    @Test
    fun `pre-place phases retain the quoted principal and fee`() {
        listOf(
            OfframpStep.INITIALIZATION,
            OfframpStep.SELECTING_CIRCLE,
            OfframpStep.FUNDING,
            OfframpStep.APPROVING_USDC,
            OfframpStep.PLACING_ORDER,
        ).forEach { step ->
            assertEquals(REQUIRED, checkpoint(step, orderId = null).pendingBaseCommitment(FEE), step.name)
        }
    }

    @Test
    fun `included place order retains only the fee until setUpi receipt`() {
        listOf(
            OfframpStep.WAITING_FOR_ACCEPTANCE,
            OfframpStep.WAITING_FOR_PAYMENT_DETAILS,
            OfframpStep.ENCRYPTING_UPI,
        ).forEach { step ->
            assertEquals(FEE, checkpoint(step, orderId = "7").pendingBaseCommitment(FEE), step.name)
        }
    }

    @Test
    fun `included setUpi receipt releases the Base commitment`() {
        listOf(
            OfframpStep.SENDING_UPI,
            OfframpStep.WAITING_FOR_COMPLETION,
        ).forEach { step ->
            assertNull(checkpoint(step, orderId = "7").pendingBaseCommitment(FEE), step.name)
        }
    }

    private fun checkpoint(step: OfframpStep, orderId: String?) =
        OfframpCheckpoint(
            orderId = orderId,
            currentStep = step,
            recipientUpi = "alice@upi",
            usdcAmountMicroDecimal = AMOUNT.micros.toString(),
            authorizedPayFeeMicroDecimal = FEE.micros.toString(),
            authorizedRequiredBalanceMicroDecimal = REQUIRED.micros.toString(),
            fiatAmountMicroDecimal = "445000000",
            currency = CurrencyCode.Inr,
            createdAtMillis = 1L,
        )

    companion object {
        private val AMOUNT = Usdc6.ofMicros(5_000_000L)
        private val FEE = Usdc6.ofMicros(100_000L)
        private val REQUIRED = Usdc6.ofMicros(5_100_000L)
    }
}
