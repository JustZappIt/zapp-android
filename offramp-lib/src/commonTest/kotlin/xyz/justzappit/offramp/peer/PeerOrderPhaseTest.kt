// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerOrderPhaseTest {
    @Test
    fun `an order with funds on offer and no buyer is waiting`() {
        assertEquals(PeerOrderPhase.WAITING, snapshot(remaining = ONE).phase)
    }

    @Test
    fun `a live intent outranks an emptied balance`() {
        val phase =
            snapshot(
                remaining = Usdc6.ZERO,
                intents = listOf(intent(ONE, PeerIntentStatus.SIGNALED)),
            ).phase
        assertEquals(PeerOrderPhase.BUYER_PAYING, phase)
    }

    @Test
    fun `an expired intent is not a live one`() {
        val phase =
            snapshot(
                remaining = ONE,
                outstandingIntentAmount = ONE,
                intents = listOf(intent(ONE, PeerIntentStatus.SIGNALED, isExpired = true)),
            ).phase
        assertEquals(PeerOrderPhase.WAITING, phase)
    }

    @Test
    fun `an order still holding funds but turned away is paused`() {
        assertEquals(PeerOrderPhase.PAUSED, snapshot(remaining = ONE, accepting = false).phase)
    }

    @Test
    fun `a sale with some left on offer is partly sold`() {
        assertEquals(PeerOrderPhase.PARTLY_SOLD, snapshot(remaining = HALF, taken = HALF).phase)
    }

    @Test
    fun `everything sold and nothing withdrawn is sold`() {
        assertEquals(PeerOrderPhase.SOLD, snapshot(remaining = Usdc6.ZERO, taken = ONE).phase)
    }

    /** The reported bug: a withdrawal drained the deposit and it read as merely paused. */
    @Test
    fun `a withdrawn order is closed rather than paused`() {
        val snap = snapshot(remaining = Usdc6.ZERO, withdrawn = ONE, accepting = false)
        assertEquals(PeerOrderPhase.CLOSED, snap.phase)
        assertTrue(snap.phase.isFinished)
    }

    @Test
    fun `selling part and withdrawing the rest is closed, not sold`() {
        assertEquals(PeerOrderPhase.CLOSED, snapshot(remaining = Usdc6.ZERO, taken = HALF, withdrawn = HALF).phase)
    }

    @Test
    fun `only settled phases are finished`() {
        assertFalse(PeerOrderPhase.WAITING.isFinished)
        assertFalse(PeerOrderPhase.BUYER_PAYING.isFinished)
        assertFalse(PeerOrderPhase.PARTLY_SOLD.isFinished)
        assertFalse(PeerOrderPhase.PAUSED.isFinished)
        assertTrue(PeerOrderPhase.SOLD.isFinished)
        assertTrue(PeerOrderPhase.CLOSED.isFinished)
    }

    private fun snapshot(
        remaining: Usdc6,
        outstandingIntentAmount: Usdc6 = Usdc6.ZERO,
        taken: Usdc6 = Usdc6.ZERO,
        withdrawn: Usdc6 = Usdc6.ZERO,
        accepting: Boolean = true,
        intents: List<PeerIntent> = emptyList(),
    ): PeerOrderSnapshot =
        PeerOrderSnapshot(
            id = PeerDepositId(escrowHex = ESCROW_HEX, onchain = "1"),
            status = PeerDepositStatus.ACTIVE,
            acceptingIntents = accepting,
            remaining = remaining,
            outstandingIntentAmount = outstandingIntentAmount,
            totalAmountTaken = taken,
            totalWithdrawn = withdrawn,
            intentAmountMin = Usdc6.ZERO,
            intentAmountMax = ONE,
            signaledIntents = 0,
            fulfilledIntents = 0,
            prunedIntents = 0,
            platform = PeerPlatform.REVOLUT,
            payeeHash = null,
            currencies = emptyList(),
            intents = intents,
            creationTxHash = null,
            creationBlockNumber = null,
            openedAtSeconds = null,
            lastActivityAtSeconds = null,
            totalIntents = intents.size,
        )

    private fun intent(
        amount: Usdc6,
        status: PeerIntentStatus,
        isExpired: Boolean = false,
    ): PeerIntent =
        PeerIntent(
            intentHash = "0x01",
            status = status,
            amount = amount,
            releasedAmount = Usdc6.ZERO,
            conversionRate = null,
            paymentCurrency = null,
            paymentAmount = PeerFiat.ZERO,
            paymentId = null,
            signalTimestampSeconds = null,
            paymentTimestampSeconds = null,
            fulfillTimestampSeconds = null,
            pruneTimestampSeconds = null,
            expiryTimeSeconds = null,
            isExpired = isExpired,
            fillLatencySeconds = null,
            signalTxHash = null,
            fulfillTxHash = null,
            pruneTxHash = null,
        )

    private companion object {
        const val ESCROW_HEX = "0x0000000000000000000000000000000000000001"
        val ONE: Usdc6 = Usdc6.ofMicros(1_000_000L)
        val HALF: Usdc6 = Usdc6.ofMicros(500_000L)
    }
}
