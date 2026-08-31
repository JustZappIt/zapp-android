// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCashOutStep
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerErrorCode
import xyz.justzappit.offramp.peer.PeerNetworks
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.PeerRecovery
import xyz.justzappit.offramp.peer.asError
import xyz.justzappit.offramp.peer.asException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Swift boundary is only as good as the codes crossing it. Every assertion here pins something
 * a Swift reducer switches on: rename one silently and a screen stops recognising the state it is
 * in, which is not a compile error on either side.
 */
class ApplePeerMappingTest {
    @Test
    fun `every status kind is distinct and every one is covered`() {
        val kinds =
            listOf(
                PeerCashOutStatus.Idle,
                PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT),
                PeerCashOutStatus.BridgingFunds(ONE),
                PeerCashOutStatus.FundedFromBase(ONE, ONE),
                PeerCashOutStatus.ApprovingUsdc(TX, ONE),
                PeerCashOutStatus.CreatingDeposit(ONE, TX),
                PeerCashOutStatus.OrderLive(peerOrderSnapshot(remaining = ONE)),
                PeerCashOutStatus.Withdrawing(DEPOSIT_ID, ONE),
                PeerCashOutStatus.Withdrawn(DEPOSIT_ID, ONE),
                PeerCashOutStatus.Failed(PeerCashOutStep.CREATING_DEPOSIT, PeerErrorCode.TRANSACTION_FAILED.asError()),
            ).map { it.toApple(SUBJECT, null).kind }

        assertEquals(
            listOf(
                ApplePeerStatus.KIND_IDLE,
                ApplePeerStatus.KIND_VALIDATING_PAYEE,
                ApplePeerStatus.KIND_FUNDED,
                ApplePeerStatus.KIND_FUNDED,
                ApplePeerStatus.KIND_APPROVING_USDC,
                ApplePeerStatus.KIND_CREATING_DEPOSIT,
                ApplePeerStatus.KIND_ORDER_LIVE,
                ApplePeerStatus.KIND_WITHDRAWING,
                ApplePeerStatus.KIND_WITHDRAWN,
                ApplePeerStatus.KIND_FAILED,
            ),
            kinds,
        )
    }

    /** Money crosses as an integer micro string, so nothing is rounded between escrow and screen. */
    @Test
    fun `amounts cross as micro strings`() {
        val status = PeerCashOutStatus.ApprovingUsdc(TX, Usdc6.ofMicros(20_500_000L)).toApple(SUBJECT, null)
        assertEquals("20500000", status.amountMicros)
        assertEquals(TX.hex, status.txHash)
    }

    /**
     * The three contracts the money depends on. Swift must read them off the failure rather than
     * re-derive them from the code, so a mapping that dropped one would be invisible until a user
     * pressed a retry that opened a second escrow.
     */
    @Test
    fun `an unknown submission outcome forbids retry and keeps the amount reserved`() {
        val failure =
            PeerCashOutStatus
                .Failed(
                    step = PeerCashOutStep.CREATING_DEPOSIT,
                    error =
                        PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN.asError(
                            recovery = PeerRecovery.InspectDepositor(DEPOSITOR),
                        ),
                ).toApple(SUBJECT, null)
                .failure

        assertNotNull(failure)
        assertEquals(PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN.name, failure.code)
        assertFalse(failure.retryable)
        assertFalse(failure.allowsManualRetry)
        assertFalse(failure.nothingEscrowed)
        assertEquals(ApplePeerFailure.RECOVERY_INSPECT_DEPOSITOR, failure.recoveryKind)
        assertEquals(DEPOSITOR.checksumHex, failure.recoveryAddress)
        assertNull(failure.recoveryTxHash)
    }

    @Test
    fun `a reverted send proves nothing was escrowed and offers a transaction to inspect`() {
        val failure =
            PeerCashOutStatus
                .Failed(
                    step = PeerCashOutStep.CREATING_DEPOSIT,
                    error =
                        PeerErrorCode.TRANSACTION_FAILED.asError(
                            recovery = PeerRecovery.InspectBaseTransaction(TX, "createDeposit"),
                        ),
                ).toApple(SUBJECT, null)
                .failure

        assertNotNull(failure)
        assertTrue(failure.nothingEscrowed)
        assertTrue(failure.allowsManualRetry)
        assertEquals(ApplePeerFailure.RECOVERY_INSPECT_TRANSACTION, failure.recoveryKind)
        assertEquals(TX.hex, failure.recoveryTxHash)
    }

    /** A local record the app cannot read settles nothing, so it must not release the reservation. */
    @Test
    fun `an unreadable recovery record keeps the amount reserved and offers no retry`() {
        val failure =
            PeerCashOutStatus
                .Failed(PeerCashOutStep.INITIALIZATION, PeerErrorCode.RECOVERY_STATE_UNREADABLE.asError())
                .toApple(SUBJECT, null)
                .failure

        assertNotNull(failure)
        assertFalse(failure.nothingEscrowed)
        assertFalse(failure.allowsManualRetry)
    }

    @Test
    fun `only typed recovery storage failures map to unreadable recovery`() {
        assertEquals(
            PeerErrorCode.RECOVERY_STATE_UNREADABLE,
            applePeerFacadeErrorCode(
                railAvailable = true,
                error = IllegalStateException("flow wrapper", ApplePeerRecoveryStorageException("unreadable")),
            ),
        )
        assertEquals(
            PeerErrorCode.INITIALIZATION_FAILED,
            applePeerFacadeErrorCode(
                railAvailable = true,
                error =
                    PeerErrorCode.INITIALIZATION_FAILED.asException(
                        cause = ApplePeerRecoveryStorageException("marker write failed"),
                    ),
            ),
        )
        assertEquals(
            PeerErrorCode.INVALID_REQUEST,
            applePeerFacadeErrorCode(railAvailable = true, error = IllegalArgumentException("bad request")),
        )
        assertEquals(
            PeerErrorCode.INITIALIZATION_FAILED,
            applePeerFacadeErrorCode(railAvailable = true, error = IllegalStateException("account construction")),
        )
    }

    /**
     * Terminal describes the status, not the operation. A live order still on offer is re-emitted by
     * the poll, and marking it terminal would stop the order screen watching it.
     */
    @Test
    fun `only a finished order is terminal`() {
        val waiting = PeerCashOutStatus.OrderLive(peerOrderSnapshot(remaining = ONE))
        val soldOut = PeerCashOutStatus.OrderLive(peerOrderSnapshot(remaining = Usdc6.ZERO, taken = ONE))

        assertFalse(waiting.toApple(SUBJECT, null).isTerminal)
        assertTrue(soldOut.toApple(SUBJECT, null).isTerminal)
        assertTrue(PeerCashOutStatus.Withdrawn(DEPOSIT_ID, ONE).toApple(SUBJECT, null).isTerminal)
    }

    /**
     * The escrow moves a deposit between four counters and never restates the original, so anything
     * describing how big the order is has to add them back up. Reading `remaining` alone makes the
     * order visibly shrink the moment a buyer locks part of it.
     */
    @Test
    fun `an order reports the size it was funded, not what is left`() {
        val order =
            peerOrderSnapshot(
                remaining = Usdc6.ofMicros(4_000_000L),
                outstanding = Usdc6.ofMicros(3_000_000L),
                taken = Usdc6.ofMicros(2_000_000L),
                withdrawn = Usdc6.ofMicros(1_000_000L),
            ).toApple(PeerNetworks.PRODUCTION)

        assertEquals("10000000", order.grossMicros)
        assertEquals("4000000", order.remainingMicros)
        assertEquals("3000000", order.lockedMicros)
        assertEquals("2000000", order.soldMicros)
        assertEquals("1000000", order.withdrawnMicros)
        assertEquals(ApplePeerOrder.PHASE_PARTLY_SOLD, order.phase)
        assertTrue(order.offersWithdrawal)
    }

    /** Peer's own explorer only indexes the production escrow, so staging must not link to it. */
    @Test
    fun `the order explorer link follows the deployment`() {
        val snapshot = peerOrderSnapshot(remaining = ONE)
        assertNotNull(snapshot.toApple(PeerNetworks.PRODUCTION).explorerUrl)
        assertNull(snapshot.toApple(PeerNetworks.STAGING).explorerUrl)
        assertNull(snapshot.toApple(null).explorerUrl)
    }

    @Test
    fun `a buyer's fiat leg is quantised to the currency they pay in`() {
        val intent =
            peerOrderSnapshot(
                remaining = Usdc6.ZERO,
                outstanding = ONE,
                intents = listOf(peerIntent(ONE, currency = PeerCurrency.EUR, fiatMinor = "9876")),
            ).toApple(null)
                .intents
                .single()

        assertEquals(ApplePeerIntent.OUTCOME_PAYING, intent.outcome)
        assertEquals("EUR", intent.paymentCurrencyCode)
        assertEquals("98.76", intent.paymentAmount)
        assertTrue(intent.holdsFunds)
        assertFalse(intent.isPaidOut)
    }

    /**
     * The rails differ in exactly two ways and both are carried. Getting the currency set wrong
     * escrows against a currency the rail cannot settle in.
     */
    @Test
    fun `platform capabilities carry the currency set and the handle rules`() {
        val byCode = PeerPlatform.entries.associate { it.wireName to it.toApple() }

        assertEquals(setOf("revolut", "zelle", "chime", "monzo"), byCode.keys)
        assertTrue(byCode.getValue("revolut").validatesHandleLive)
        assertTrue(byCode.getValue("revolut").offersCurrencyChoice)
        assertEquals(PeerCurrency.entries.size, byCode.getValue("revolut").currencies.size)

        assertFalse(byCode.getValue("zelle").validatesHandleLive)
        assertFalse(byCode.getValue("zelle").offersCurrencyChoice)
        assertEquals(listOf("USD"), byCode.getValue("zelle").currencies.map { it.code })
        assertEquals(listOf("GBP"), byCode.getValue("monzo").currencies.map { it.code })
    }

    private companion object {
        const val SUBJECT = "0123456789abcdef0123456789abcdef"
        val ONE: Usdc6 = Usdc6.ofMicros(1_000_000L)
        val TX: TxHash = TxHash.fromHex("0x" + "ab".repeat(TxHash.LEN))
        val DEPOSITOR: Address = Address.parse("0x00000000000000000000000000000000000000aa")
        val DEPOSIT_ID = peerDepositId()
    }
}
