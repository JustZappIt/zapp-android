package co.electriccoin.zcash.ui.common.repository

import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerDepositId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The withdraw button used to come back to life for the moment between the transaction settling and
 * the order poll reporting it, which is long enough to tap and send a second one.
 */
class PeerOrderActionRunTest {
    @Test
    fun `a running action holds the buttons closed`() {
        assertTrue(run(isRunning = true, settledAtMillis = null).awaitsConfirmation(readAtMillis = 1_000L))
    }

    @Test
    fun `a settled action holds them closed while the newest read predates it`() {
        assertTrue(run(isRunning = false, settledAtMillis = 2_000L).awaitsConfirmation(readAtMillis = 1_999L))
    }

    @Test
    fun `a settled action releases once a later read lands`() {
        assertFalse(run(isRunning = false, settledAtMillis = 2_000L).awaitsConfirmation(readAtMillis = 2_001L))
    }

    /** Nothing has been read yet, so nothing on screen can justify offering the action again. */
    @Test
    fun `a settled action holds them closed when no read has landed`() {
        assertTrue(run(isRunning = false, settledAtMillis = 2_000L).awaitsConfirmation(readAtMillis = null))
    }

    @Test
    fun `an action that never ran does not hold anything closed`() {
        assertFalse(run(isRunning = false, settledAtMillis = null).awaitsConfirmation(readAtMillis = null))
    }

    private fun run(isRunning: Boolean, settledAtMillis: Long?) =
        PeerOrderActionRun(
            depositId = PeerDepositId(escrowHex = ESCROW_HEX, onchain = "1"),
            kind = PeerOrderActionKind.WITHDRAW,
            latest = PeerCashOutStatus.Idle,
            isRunning = isRunning,
            settledAtMillis = settledAtMillis,
        )

    private companion object {
        const val ESCROW_HEX = "0x0000000000000000000000000000000000000001"
    }
}
