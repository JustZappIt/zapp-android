// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The indexer stores `depositor` EIP-55 checksummed (`0x4a96C8Eb…`) while every address in this
 * codebase is lowercase, so a case-sensitive `_eq` matched nothing on a live order. It reported "no
 * orders" on the cash-out screen and left the reconcile paths unable to find a deposit they had just
 * created.
 *
 * Hex has no LIKE metacharacters, so `_ilike` on a full address is an exact case-insensitive match.
 */
class PeerIndexerQueryTest {
    @Test
    fun `depositor queries match regardless of address casing`() {
        listOf(
            PeerIndexerClient.ACTIVE_DEPOSITS_BY_DEPOSITOR_QUERY,
            PeerIndexerClient.ALL_DEPOSITS_BY_DEPOSITOR_QUERY,
        ).forEach { document ->
            assertTrue(document.contains("depositor: { _ilike:"), "depositor filter must be case-insensitive")
            assertFalse(document.contains("depositor: { _eq:"), "a case-sensitive depositor filter matches nothing")
        }
    }

    /** Everything else compares a hash the indexer also stores lowercase, so it stays exact. */
    @Test
    fun `status and payment method filters stay exact`() {
        assertTrue(PeerIndexerClient.ACTIVE_DEPOSITS_BY_DEPOSITOR_QUERY.contains("status: { _eq: \"ACTIVE\" }"))
        assertTrue(PeerIndexerClient.QUEUE_SAMPLES_QUERY.contains("paymentMethodHash: { _eq:"))
    }

    /**
     * An unordered nested selection is an arbitrary page, so the buyer list would render a random
     * subset and [PeerOrderSnapshot.expiredIntentAmount] would sum a random subtotal of the funds a
     * withdrawal has to prune.
     */
    @Test
    fun `every deposit query reads a defined page of intents`() {
        listOf(
            PeerIndexerClient.ORDER_QUERY,
            PeerIndexerClient.ACTIVE_DEPOSITS_BY_DEPOSITOR_QUERY,
            PeerIndexerClient.ALL_DEPOSITS_BY_DEPOSITOR_QUERY,
        ).forEach { document ->
            assertTrue(
                document.contains("intents(order_by: { signalTimestamp: desc }, limit: "),
                "the nested intents selection must be ordered and bounded",
            )
            assertTrue(document.contains("limit: ${PeerIndexerClient.INTENT_PAGE_LIMIT}"))
        }
    }

    @Test
    fun `every deposit query asks for what the order surfaces render`() {
        listOf(
            PeerIndexerClient.ORDER_QUERY,
            PeerIndexerClient.ACTIVE_DEPOSITS_BY_DEPOSITOR_QUERY,
            PeerIndexerClient.ALL_DEPOSITS_BY_DEPOSITOR_QUERY,
        ).forEach { document ->
            listOf(
                "timestamp",
                "updatedAt",
                "totalIntents",
                "paymentTimestamp",
                "pruneTimestamp",
                "signalTxHash",
                "fulfillTxHash",
                "pruneTxHash",
            ).forEach { field ->
                assertTrue(document.contains(field), "deposit query must select $field")
            }
        }
    }
}
