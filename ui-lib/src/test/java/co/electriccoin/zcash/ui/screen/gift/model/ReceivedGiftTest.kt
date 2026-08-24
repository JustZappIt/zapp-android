// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceivedGiftTest {
    @Test
    fun `prepared write cannot erase submitted txids`() {
        val submitted = receipt(claimTxids = listOf("tx-1"))
        val prepared = receipt(claimTxids = emptyList())

        assertEquals(listOf("tx-1"), listOf(submitted).recording(prepared).single().claimTxids)
    }

    @Test
    fun `stale write cannot regress final receipt`() {
        val settled = receipt(claimLink = null, isFinalized = true)

        val result = listOf(settled).recording(receipt()).single()

        assertTrue(result.isSettled)
        assertNull(result.claimLink)
    }

    @Test
    fun `transactions from the same attempt are merged`() {
        val result =
            listOf(receipt(claimTxids = listOf("tx-1")))
                .recording(receipt(claimTxids = listOf("tx-1", "tx-2")))

        assertEquals(listOf("tx-1", "tx-2"), result.single().claimTxids)
    }

    @Test
    fun `a retry replaces expired attempt txids`() {
        val result = listOf(receipt(claimTxids = listOf("expired"))).recording(receipt(claimTxids = listOf("retry")))

        assertEquals(listOf("retry"), result.single().claimTxids)
    }

    @Test
    fun `new submission marker clears expired ids and stale finalization checkpoint`() {
        val previous =
            receipt(
                claimTxids = listOf("expired"),
                claimSubmissionAttemptedAt = "attempt-1",
                isFinalized = true,
            )

        val result =
            listOf(previous)
                .recording(
                    receipt(
                        claimTxids = emptyList(),
                        claimSubmissionAttemptedAt = "attempt-2",
                    )
                ).single()

        assertTrue(result.claimTxids.isEmpty())
        assertEquals(false, result.isFinalized)
    }

    @Test
    fun `unsettled retry remains pinned to its original destination`() {
        val previous =
            receipt(
                destinationAddress = "original-address",
                destinationAccountUuid = "original-account",
            )

        val result =
            listOf(previous)
                .recording(
                    receipt(
                        destinationAddress = "newly-selected-address",
                        destinationAccountUuid = "newly-selected-account",
                    )
                ).single()

        assertEquals("original-address", result.destinationAddress)
        assertEquals("original-account", result.destinationAccountUuid)
    }

    private fun receipt(
        claimTxids: List<String> = emptyList(),
        claimSubmissionAttemptedAt: String? = null,
        claimLink: GiftLinkPayload? = PAYLOAD,
        isFinalized: Boolean = false,
        destinationAddress: String = "wallet-address",
        destinationAccountUuid: String? = null,
    ) = ReceivedGift(
        address = "card-address",
        network = "main",
        amountZatoshi = 100_000_000L,
        claimedAt = "2026-08-23T00:00:00Z",
        destinationAddress = destinationAddress,
        destinationAccountUuid = destinationAccountUuid,
        claimTxids = claimTxids,
        claimSubmissionAttemptedAt = claimSubmissionAttemptedAt,
        claimLink = claimLink,
        isFinalized = isFinalized,
    )

    @Test
    fun `a receipt for a card this wallet only read holds no recovery material`() {
        val read = receipt(claimTxids = emptyList(), claimSubmissionAttemptedAt = null)

        assertFalse(read.hasClaimAttempt)
        assertFalse(read.isUnsettledClaim, "nothing was created, so there is nothing to recover")
    }

    @Test
    fun `the submission marker alone makes a receipt custody`() {
        // Past the marker the transaction may exist whatever came back, so the link is the only
        // route to a card this wallet may already have spent from.
        val started = receipt(claimTxids = emptyList(), claimSubmissionAttemptedAt = "2026-08-23T00:01:00Z")

        assertTrue(started.hasClaimAttempt)
        assertTrue(started.isUnsettledClaim)
    }

    @Test
    fun `a settled receipt is no longer unsettled work`() {
        assertFalse(receipt(claimTxids = listOf("tx-1"), claimLink = null).isUnsettledClaim)
    }

    @Test
    fun `discards only a receipt that never started a claim`() {
        val read = receipt(claimTxids = emptyList())

        assertTrue(listOf(read).discardingUnstarted("card-address").isEmpty())
    }

    @Test
    fun `discard leaves every receipt that could still need its link`() {
        val submitted = receipt(claimTxids = listOf("tx-1"))
        val started = receipt(claimTxids = emptyList(), claimSubmissionAttemptedAt = "2026-08-23T00:01:00Z")
        val settled = receipt(claimTxids = listOf("tx-1"), claimLink = null)
        val elsewhere = receipt(claimTxids = emptyList()).copy(isClaimedElsewhere = true)

        listOf(submitted, started, settled, elsewhere).forEach { kept ->
            assertEquals(
                listOf(kept),
                listOf(kept).discardingUnstarted("card-address"),
                "a receipt with claimTxids=${kept.claimTxids}, marker=${kept.claimSubmissionAttemptedAt}, " +
                    "settled=${kept.isSettled}, elsewhere=${kept.isClaimedElsewhere} must survive a discard",
            )
        }
    }

    @Test
    fun `discard leaves other cards alone`() {
        val other = receipt(claimTxids = emptyList()).copy(address = "another-card")

        assertEquals(listOf(other), listOf(other).discardingUnstarted("card-address"))
    }

    private companion object {
        val PAYLOAD =
            GiftLinkPayload(
                v = 1,
                network = "main",
                amountZatoshi = "100000000",
                mnemonic = "test mnemonic",
                birthdayHeight = 1L,
                createdAt = "2026-08-23T00:00:00Z",
            )
    }
}
