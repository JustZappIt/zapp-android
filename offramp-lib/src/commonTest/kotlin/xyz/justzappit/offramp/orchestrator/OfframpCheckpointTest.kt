// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

class OfframpCheckpointTest {
    @Test
    fun `toRequest preserves persisted fiat amount limit separately from fiat amount`() {
        val request =
            OfframpCheckpoint(
                orderId = null,
                currentStep = OfframpStep.FUNDING,
                recipientUpi = "merchant@upi",
                usdcAmountMicroDecimal = "5000000",
                fiatAmountMicroDecimal = "445000000",
                fiatAmountLimitMicroDecimal = "444000000",
                currency = CurrencyCode.Inr,
                createdAtMillis = 0L,
            ).toRequest(fallbackFiatAmount = Usdc6.ofMicros(400_000_000))

        assertEquals(Usdc6.ofMicros(445_000_000), request.fiatAmount)
        assertEquals(Usdc6.ofMicros(444_000_000), request.fiatAmountLimit)
    }
}
