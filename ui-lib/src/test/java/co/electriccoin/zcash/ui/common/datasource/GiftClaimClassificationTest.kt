// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GiftClaimClassificationTest {
    @Test
    fun `final spend without local submission evidence belongs to another holder`() {
        assertEquals(
            GiftOutgoingClaimDisposition.ALREADY_CLAIMED,
            classifyOutgoingGiftClaim(
                finalTxIds = setOf("foreign"),
                pendingTxIds = emptySet(),
                locallySubmittedTxIds = emptySet(),
            ),
        )
    }

    @Test
    fun `pending spend without local evidence waits because it may expire`() {
        assertEquals(
            GiftOutgoingClaimDisposition.AWAITING_FINALITY,
            classifyOutgoingGiftClaim(
                finalTxIds = emptySet(),
                pendingTxIds = setOf("foreign"),
                locallySubmittedTxIds = emptySet(),
            ),
        )
    }

    @Test
    fun `known transaction id resumes this wallets claim`() {
        assertEquals(
            GiftOutgoingClaimDisposition.RESUME,
            classifyOutgoingGiftClaim(
                finalTxIds = setOf("ours"),
                pendingTxIds = emptySet(),
                locallySubmittedTxIds = setOf("ours"),
            ),
        )
    }

    @Test
    fun `marker recovery resumes only a transaction matched to the pinned recipient`() {
        assertEquals(
            GiftOutgoingClaimDisposition.RESUME,
            classifyOutgoingGiftClaim(
                finalTxIds = emptySet(),
                pendingTxIds = setOf("ours"),
                locallySubmittedTxIds = setOf("ours"),
            ),
        )
    }

    @Test
    fun `marker without a recipient match does not adopt a foreign final claim`() {
        assertEquals(
            GiftOutgoingClaimDisposition.ALREADY_CLAIMED,
            classifyOutgoingGiftClaim(
                finalTxIds = setOf("foreign"),
                pendingTxIds = emptySet(),
                locallySubmittedTxIds = emptySet(),
            ),
        )
    }

    @Test
    fun `marker without a recipient match waits for a foreign pending claim`() {
        assertEquals(
            GiftOutgoingClaimDisposition.AWAITING_FINALITY,
            classifyOutgoingGiftClaim(
                finalTxIds = emptySet(),
                pendingTxIds = setOf("foreign"),
                locallySubmittedTxIds = emptySet(),
            ),
        )
    }

    @Test
    fun `large input with small external outflow is not a completed claim spend`() {
        val transaction =
            mockk<TransactionOverview> {
                every { isSentTransaction } returns true
                every { totalSpent } returns Zatoshi(CARD_AMOUNT + FEE_RESERVE)
                every { netValue } returns Zatoshi(FEE_RESERVE)
                every { transactionState } returns TransactionState.Confirmed
            }

        assertFalse(transaction.isFinalClaimSpend(Zatoshi(CARD_AMOUNT)))
    }

    @Test
    fun `external outflow covering the card amount is a completed claim spend`() {
        val transaction =
            mockk<TransactionOverview> {
                every { isSentTransaction } returns true
                every { totalSpent } returns Zatoshi(CARD_AMOUNT + FEE_RESERVE)
                every { netValue } returns Zatoshi(CARD_AMOUNT)
                every { transactionState } returns TransactionState.Confirmed
            }

        assertTrue(transaction.isFinalClaimSpend(Zatoshi(CARD_AMOUNT)))
    }

    private companion object {
        const val CARD_AMOUNT = 100_000_000L
        const val FEE_RESERVE = 10_000L
    }
}
