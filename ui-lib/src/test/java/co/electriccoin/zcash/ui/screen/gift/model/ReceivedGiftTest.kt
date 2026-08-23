// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun receipt(
        claimTxids: List<String> = emptyList(),
        claimLink: GiftLinkPayload? = PAYLOAD,
        isFinalized: Boolean = false,
    ) = ReceivedGift(
        address = "card-address",
        network = "main",
        amountZatoshi = 100_000_000L,
        claimedAt = "2026-08-23T00:00:00Z",
        destinationAddress = "wallet-address",
        claimTxids = claimTxids,
        claimLink = claimLink,
        isFinalized = isFinalized,
    )

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
