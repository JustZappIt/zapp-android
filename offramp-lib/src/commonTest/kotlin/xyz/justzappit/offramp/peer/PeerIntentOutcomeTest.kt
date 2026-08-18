// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerIntentOutcomeTest {
    @Test
    fun `a prune before the deadline is a buyer backing out`() {
        val outcome =
            intent(
                status = PeerIntentStatus.PRUNED,
                signalSeconds = SIGNAL,
                pruneSeconds = SIGNAL + 38,
                expirySeconds = SIGNAL + WINDOW,
            ).outcome
        assertEquals(PeerIntentOutcome.BACKED_OUT, outcome)
    }

    @Test
    fun `a prune at the deadline is an expiry`() {
        val outcome =
            intent(
                status = PeerIntentStatus.PRUNED,
                signalSeconds = SIGNAL,
                pruneSeconds = SIGNAL + WINDOW,
                expirySeconds = SIGNAL + WINDOW,
            ).outcome
        assertEquals(PeerIntentOutcome.TIMED_OUT, outcome)
    }

    @Test
    fun `a prune with no timestamps falls back to the indexer's flag`() {
        val outcome = intent(status = PeerIntentStatus.PRUNED, isExpired = true).outcome
        assertEquals(PeerIntentOutcome.TIMED_OUT, outcome)
    }

    @Test
    fun `a live intent is paying and an expired one is out of time`() {
        assertEquals(PeerIntentOutcome.PAYING, intent(status = PeerIntentStatus.SIGNALED).outcome)
        assertEquals(
            PeerIntentOutcome.OUT_OF_TIME,
            intent(status = PeerIntentStatus.SIGNALED, isExpired = true).outcome,
        )
    }

    @Test
    fun `a manual release counts as paid`() {
        assertEquals(PeerIntentOutcome.PAID, intent(status = PeerIntentStatus.MANUALLY_RELEASED).outcome)
    }

    @Test
    fun `time left to pay only exists while a buyer is on the clock`() {
        val live =
            intent(
                status = PeerIntentStatus.SIGNALED,
                signalSeconds = SIGNAL,
                expirySeconds = SIGNAL + WINDOW,
            )
        assertEquals(WINDOW - 600, live.secondsLeftToPay(SIGNAL + 600))
        assertNull(live.secondsLeftToPay(SIGNAL + WINDOW))
        assertNull(
            intent(
                status = PeerIntentStatus.PRUNED,
                signalSeconds = SIGNAL,
                expirySeconds = SIGNAL + WINDOW,
            ).secondsLeftToPay(SIGNAL),
        )
    }

    @Test
    fun `how long a lock held funds needs both of its stamps`() {
        assertEquals(
            38L,
            intent(
                status = PeerIntentStatus.PRUNED,
                signalSeconds = SIGNAL,
                pruneSeconds = SIGNAL + 38,
            ).heldForSeconds,
        )
        assertNull(intent(status = PeerIntentStatus.PRUNED, signalSeconds = SIGNAL).heldForSeconds)
    }

    @Test
    fun `the settlement link prefers the transaction that ended the leg`() {
        val paid =
            intent(
                status = PeerIntentStatus.FULFILLED,
                signalTxHash = TX_A,
                fulfillTxHash = TX_B,
            )
        assertEquals(TX_B, paid.settlementTxHash)
        assertEquals(TX_A, intent(status = PeerIntentStatus.SIGNALED, signalTxHash = TX_A).settlementTxHash)
    }

    private fun intent(
        status: PeerIntentStatus,
        isExpired: Boolean = false,
        signalSeconds: Long? = null,
        pruneSeconds: Long? = null,
        expirySeconds: Long? = null,
        signalTxHash: TxHash? = null,
        fulfillTxHash: TxHash? = null,
    ): PeerIntent =
        PeerIntent(
            intentHash = "0x01",
            status = status,
            amount = Usdc6.ofMicros(1_000_000L),
            releasedAmount = Usdc6.ZERO,
            conversionRate = null,
            paymentCurrency = null,
            paymentAmount = PeerFiat.ZERO,
            paymentId = null,
            signalTimestampSeconds = signalSeconds,
            paymentTimestampSeconds = null,
            fulfillTimestampSeconds = null,
            pruneTimestampSeconds = pruneSeconds,
            expiryTimeSeconds = expirySeconds,
            isExpired = isExpired,
            fillLatencySeconds = null,
            signalTxHash = signalTxHash,
            fulfillTxHash = fulfillTxHash,
            pruneTxHash = null,
        )

    private companion object {
        const val SIGNAL = 1_786_577_271L
        const val WINDOW = 21_600L
        val TX_A = TxHash.fromHex("0x" + "11".repeat(32))
        val TX_B = TxHash.fromHex("0x" + "22".repeat(32))
    }
}
