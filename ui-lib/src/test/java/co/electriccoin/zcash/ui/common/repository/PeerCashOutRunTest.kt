package co.electriccoin.zcash.ui.common.repository

import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCashOutStep
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerErrorCode
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.asError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What an attempt still has a claim on. Reserving too little lets one balance fund two cash-outs;
 * reserving too much hides the user's own money behind an attempt that is already over.
 */
class PeerCashOutRunTest {
    @Test
    fun `an attempt that has not reached the send still holds its amount`() {
        assertTrue(run().holdsFunds)
        assertTrue(run(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT)).holdsFunds)
    }

    @Test
    fun `a failure before the send releases what it never committed`() {
        val failed =
            run(
                PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT),
                failure(PeerCashOutStep.VALIDATING_PAYEE, PeerErrorCode.PAYEE_NOT_FOUND_ON_PLATFORM),
            )
        assertFalse(failed.holdsFunds)
    }

    /** The reported bug: a proven revert escrowed nothing, yet the amount stayed reserved forever. */
    @Test
    fun `a conclusively reverted send releases the amount it did not escrow`() {
        val reverted =
            run(
                creatingDeposit(),
                failure(PeerCashOutStep.CREATING_DEPOSIT, PeerErrorCode.TRANSACTION_FAILED),
            )
        assertFalse(reverted.holdsFunds)
    }

    @Test
    fun `a send whose outcome is unknown keeps the amount reserved`() {
        val unknown =
            run(
                creatingDeposit(),
                failure(PeerCashOutStep.CREATING_DEPOSIT, PeerErrorCode.TRANSACTION_STATUS_UNKNOWN),
            )
        assertTrue(unknown.holdsFunds)
        assertTrue(unknown.isUnindexed)
    }

    /**
     * The escrow already holds the amount once reconciliation names the order, so a run that keeps
     * reserving it too is what subtracts it from the balance twice.
     */
    @Test
    fun `a reconciled deposit settles an attempt whose statuses never named one`() {
        val unknown =
            run(
                creatingDeposit(),
                failure(PeerCashOutStep.CREATING_DEPOSIT, PeerErrorCode.TRANSACTION_STATUS_UNKNOWN),
            ).copy(reconciledDepositId = DEPOSIT_ID)

        assertEquals(DEPOSIT_ID, unknown.depositId)
        assertFalse(unknown.holdsFunds)
        assertFalse(unknown.isUnindexed)
    }

    private fun run(vararg statuses: PeerCashOutStatus) =
        PeerCashOutRun(
            id = ID,
            platform = PeerPlatform.REVOLUT,
            amount = Usdc6.ofMicros(ONE_USDC),
            currencies = listOf(PeerCurrency.EUR),
            statuses = statuses.toList(),
            startedAtMillis = 0L,
        )

    private fun creatingDeposit() =
        PeerCashOutStatus.CreatingDeposit(amount = Usdc6.ofMicros(ONE_USDC), submissionHash = SUBMISSION_HASH)

    private fun failure(step: PeerCashOutStep, code: PeerErrorCode) =
        PeerCashOutStatus.Failed(step = step, error = code.asError())

    private companion object {
        const val ONE_USDC = 1_000_000L
        val ID: PeerCashOutId = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 7 })
        val DEPOSIT_ID: PeerDepositId =
            PeerDepositId(escrowHex = "0x0000000000000000000000000000000000000001", onchain = "7")
        val SUBMISSION_HASH: TxHash =
            TxHash.fromHex("0x${"12".repeat(TxHash.LEN)}")
    }
}
