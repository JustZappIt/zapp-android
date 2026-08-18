// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reported bug: both order surfaces offered the matching toggle on `remaining > 0`, which
 * `withdrawableAfterPrune` already covers, so the toggle was unreachable — and the one case it was
 * written for, live intents holding the whole balance, reached neither branch and offered nothing.
 */
class PeerOrderControlsTest {
    @Test
    fun `funds on offer are withdrawn rather than toggled`() {
        val snap = snapshot(remaining = ONE)
        assertTrue(snap.offersWithdrawal)
        assertFalse(snap.offersMatchingToggle)
    }

    @Test
    fun `an expired intent is pruned and withdrawn, not toggled`() {
        val snap =
            snapshot(
                remaining = Usdc6.ZERO,
                outstandingIntentAmount = ONE,
                intents = listOf(intent(ONE, isExpired = true)),
            )
        assertTrue(snap.offersWithdrawal)
        assertFalse(snap.offersMatchingToggle)
    }

    /** The case the comments described and neither surface could reach. */
    @Test
    fun `a live intent holding the whole balance offers the matching toggle`() {
        val snap =
            snapshot(
                remaining = Usdc6.ZERO,
                outstandingIntentAmount = ONE,
                intents = listOf(intent(ONE)),
            )
        assertFalse(snap.offersWithdrawal)
        assertTrue(snap.offersMatchingToggle)
    }

    @Test
    fun `a partly taken order still offers what is left rather than the toggle`() {
        val snap =
            snapshot(
                remaining = HALF,
                outstandingIntentAmount = HALF,
                intents = listOf(intent(HALF)),
            )
        assertTrue(snap.offersWithdrawal)
        assertFalse(snap.offersMatchingToggle)
    }

    @Test
    fun `a finished order offers neither`() {
        val sold = snapshot(remaining = Usdc6.ZERO, taken = ONE)
        assertFalse(sold.offersWithdrawal)
        assertFalse(sold.offersMatchingToggle)

        val closed = snapshot(remaining = Usdc6.ZERO, withdrawn = ONE)
        assertFalse(closed.offersWithdrawal)
        assertFalse(closed.offersMatchingToggle)
    }

    private fun snapshot(
        remaining: Usdc6,
        outstandingIntentAmount: Usdc6 = Usdc6.ZERO,
        taken: Usdc6 = Usdc6.ZERO,
        withdrawn: Usdc6 = Usdc6.ZERO,
        intents: List<PeerIntent> = emptyList(),
    ): PeerOrderSnapshot =
        PeerOrderSnapshot(
            id = PeerDepositId(escrowHex = ESCROW_HEX, onchain = "1"),
            status = PeerDepositStatus.ACTIVE,
            acceptingIntents = true,
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

    private fun intent(amount: Usdc6, isExpired: Boolean = false): PeerIntent =
        PeerIntent(
            intentHash = "0x01",
            status = PeerIntentStatus.SIGNALED,
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
