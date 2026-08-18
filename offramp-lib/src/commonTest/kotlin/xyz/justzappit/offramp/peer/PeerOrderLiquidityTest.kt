// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The escrow moves a deposit between four counters and never restates the original, so every number
 * the cash-out screens show is arithmetic over those counters. The bugs this pins are all one
 * mistake: reading `remainingDeposits` as a gross figure that intents have yet to be taken out of,
 * when the escrow has already taken them out.
 */
class PeerOrderLiquidityTest {
    /**
     * The reported bug. 6.46 free with 23.04 locked by a buyer reported nothing withdrawable, and
     * the order it belonged to offered no way to reach the 6.46.
     */
    @Test
    fun `a balance a buyer has not taken stays withdrawable`() {
        val snap = snapshot(remaining = usdc(6_460_000), outstanding = usdc(23_040_000))

        assertEquals(usdc(6_460_000), snap.withdrawableAfterPrune)
        assertEquals(usdc(29_500_000), snap.grossAmount)
    }

    @Test
    fun `pruning adds back only what expired, never the whole outstanding total`() {
        val snap =
            snapshot(
                remaining = usdc(1_000_000),
                outstanding = usdc(3_000_000),
                intents =
                    listOf(
                        intent(usdc(2_000_000), PeerIntentStatus.SIGNALED, isExpired = true),
                        intent(usdc(1_000_000), PeerIntentStatus.SIGNALED),
                    ),
            )

        assertEquals(usdc(2_000_000), snap.expiredIntentAmount)
        assertEquals(usdc(3_000_000), snap.withdrawableAfterPrune)
    }

    /** The counter is the escrow's own arithmetic; a stale intent page must not outvote it. */
    @Test
    fun `the expired total is capped at what the escrow says is outstanding`() {
        val snap =
            snapshot(
                remaining = Usdc6.ZERO,
                outstanding = usdc(1_000_000),
                intents =
                    listOf(
                        intent(usdc(4_000_000), PeerIntentStatus.SIGNALED, isExpired = true),
                    ),
            )

        assertEquals(usdc(1_000_000), snap.expiredIntentAmount)
        assertEquals(usdc(1_000_000), snap.withdrawableAfterPrune)
    }

    /**
     * An order whose whole balance sits behind expired intents used to read as closed, which filed
     * it away before the user was ever offered the prune-and-withdraw that would free the money.
     */
    @Test
    fun `an order held entirely by expired intents is still open`() {
        val snap =
            snapshot(
                remaining = Usdc6.ZERO,
                outstanding = usdc(5_000_000),
                intents = listOf(intent(usdc(5_000_000), PeerIntentStatus.SIGNALED, isExpired = true)),
            )

        assertEquals(PeerOrderPhase.WAITING, snap.phase)
        assertFalse(snap.phase.isFinished)
        assertTrue(snap.hasExpiredIntentHoldingFunds)
        assertEquals(usdc(5_000_000), snap.withdrawableAfterPrune)
    }

    @Test
    fun `a drained order is still closed`() {
        val snap = snapshot(remaining = Usdc6.ZERO, withdrawn = usdc(5_000_000))

        assertEquals(PeerOrderPhase.CLOSED, snap.phase)
        assertEquals(Usdc6.ZERO, snap.withdrawableAfterPrune)
    }

    /** Order size must not shrink when a buyer locks part of it, nor when part is returned. */
    @Test
    fun `the gross size counts what sold and what came back`() {
        val snap =
            snapshot(
                remaining = usdc(1_000_000),
                outstanding = usdc(2_000_000),
                taken = usdc(3_000_000),
                withdrawn = usdc(4_000_000),
            )

        assertEquals(usdc(10_000_000), snap.grossAmount)
    }

    /**
     * Two cash-outs of the same size on the same rail, minutes apart, are told apart by what they
     * offered. Without the currencies a recovery could adopt the other one's order.
     */
    @Test
    fun `recovery will not adopt an order offering different currencies`() {
        val checkpoint =
            PeerCashOutCheckpoint(
                id = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 7 }),
                platform = PeerPlatform.REVOLUT,
                currencies = listOf(PeerCurrency.EUR),
                payeeHashHex = PAYEE_HEX,
                amountMicroDecimal = ONE.micros.toString(),
                createdAtMillis = 0L,
            )
        val sameCurrency = snapshot(remaining = ONE, currencies = listOf(PeerCurrency.EUR))
        val otherCurrency = snapshot(remaining = ONE, currencies = listOf(PeerCurrency.USD))

        assertTrue(sameCurrency.couldHaveBeenOpenedBy(checkpoint, notBeforeBlock = null))
        assertFalse(otherCurrency.couldHaveBeenOpenedBy(checkpoint, notBeforeBlock = null))
    }

    /**
     * Posting at the intent floor collapses the range onto itself, which is what left three 1 USDC
     * deposits with no buyer. The order is still valid, so this reads as a fact to show, not a fault.
     */
    @Test
    fun `an order posted at the intent floor can only be taken whole`() {
        val snap = snapshot(remaining = ONE, intentMin = ONE, intentMax = ONE)

        assertTrue(snap.isAllOrNothing)
    }

    @Test
    fun `an order a buyer can take a slice of is not all or nothing`() {
        val snap = snapshot(remaining = usdc(5_000_000), intentMin = ONE, intentMax = usdc(5_000_000))

        assertFalse(snap.isAllOrNothing)
    }

    @Test
    fun `a deposit reporting no minimum is not called all or nothing`() {
        val snap = snapshot(remaining = ONE, intentMin = Usdc6.ZERO, intentMax = Usdc6.ZERO)

        assertFalse(snap.isAllOrNothing)
    }

    /**
     * The orderbook filters on what is left, so an order that sold most of itself goes dark holding
     * the tail. Observed on live deposits that took 31 USDC and then stopped being offered at 2.
     */
    @Test
    fun `an order that sold down past the threshold is hidden from buyers`() {
        val snap = snapshot(remaining = usdc(2_000_000), taken = usdc(31_000_000))

        assertTrue(snap.isHiddenFromBuyers)
    }

    @Test
    fun `an order sitting exactly at the threshold is still shown`() {
        val snap = snapshot(remaining = usdc(5_000_000))

        assertFalse(snap.isHiddenFromBuyers)
    }

    @Test
    fun `an order with nothing left is not called hidden`() {
        val snap = snapshot(remaining = Usdc6.ZERO, taken = usdc(5_000_000))

        assertFalse(snap.isHiddenFromBuyers)
    }

    /** The second bound: an order sitting above the listing floor still goes dark on its own minimum. */
    @Test
    fun `an order whose residual is under its own minimum is hidden from buyers`() {
        val snap = snapshot(remaining = usdc(6_000_000), intentMin = usdc(10_000_000), taken = usdc(4_000_000))

        assertTrue(snap.isHiddenFromBuyers)
    }

    private fun snapshot(
        remaining: Usdc6,
        outstanding: Usdc6 = Usdc6.ZERO,
        taken: Usdc6 = Usdc6.ZERO,
        withdrawn: Usdc6 = Usdc6.ZERO,
        currencies: List<PeerCurrency> = emptyList(),
        intents: List<PeerIntent> = emptyList(),
        intentMin: Usdc6 = Usdc6.ZERO,
        intentMax: Usdc6 = ONE,
    ): PeerOrderSnapshot =
        PeerOrderSnapshot(
            id = PeerDepositId(escrowHex = ESCROW_HEX, onchain = "1"),
            status = PeerDepositStatus.ACTIVE,
            acceptingIntents = true,
            remaining = remaining,
            outstandingIntentAmount = outstanding,
            totalAmountTaken = taken,
            totalWithdrawn = withdrawn,
            intentAmountMin = intentMin,
            intentAmountMax = intentMax,
            signaledIntents = 0,
            fulfilledIntents = 0,
            prunedIntents = 0,
            platform = PeerPlatform.REVOLUT,
            payeeHash = PayeeHash.parse(PAYEE_HEX),
            currencies =
                currencies.map {
                    PeerOrderCurrency(
                        currency = it,
                        spread = Bps(0),
                        oracleRate = null,
                        lastOracleUpdatedAtSeconds = null,
                    )
                },
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

    private fun usdc(micros: Long): Usdc6 = Usdc6.ofMicros(micros)

    private companion object {
        const val ESCROW_HEX = "0x0000000000000000000000000000000000000001"
        const val PAYEE_HEX = "0x2222222222222222222222222222222222222222222222222222222222222222"
        val ONE: Usdc6 = Usdc6.ofMicros(1_000_000L)
    }
}
