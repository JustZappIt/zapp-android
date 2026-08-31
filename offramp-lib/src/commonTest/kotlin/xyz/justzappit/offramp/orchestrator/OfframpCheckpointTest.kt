// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `financial identifiers and amounts fail closed when malformed`() {
        listOf("0", "-1", "not-an-amount").forEach { amount ->
            assertFailsWith<IllegalArgumentException> { checkpoint(amount = amount) }
        }
        assertFailsWith<IllegalArgumentException> { checkpoint(orderId = "-1") }
        assertFailsWith<IllegalArgumentException> {
            checkpoint(
                authorizedFee = "100000",
                authorizedRequired = "5000000",
            )
        }
    }

    @Test
    fun `a successful legacy place hash does not restore permanent nonce ownership`() {
        val hash = TxHash.fromHex("0x" + "11".repeat(TxHash.LEN))

        assertEquals(false, checkpoint(orderId = "7").copy(placeOrderTxHash = hash).hasUnresolvedPlaceSubmission)
        assertEquals(true, checkpoint(orderId = null).copy(placeOrderTxHash = hash).hasUnresolvedPlaceSubmission)
    }

    private fun checkpoint(
        orderId: String? = null,
        amount: String = "5000000",
        authorizedFee: String? = null,
        authorizedRequired: String? = null,
    ) =
        OfframpCheckpoint(
            orderId = orderId,
            currentStep = OfframpStep.FUNDING,
            recipientUpi = "merchant@upi",
            usdcAmountMicroDecimal = amount,
            authorizedPayFeeMicroDecimal = authorizedFee,
            authorizedRequiredBalanceMicroDecimal = authorizedRequired,
            fiatAmountMicroDecimal = "445000000",
            currency = CurrencyCode.Inr,
            createdAtMillis = 0L,
        )
}
