// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The money-guarding half of the cash-out lifecycle. Two attempts must stay distinguishable, and a
 * balance must not be spendable twice.
 */
class PeerCashOutLifecycleTest {
    private val payeeHashHex = "0x" + "11".repeat(BYTES32)

    @Test
    fun `cash-out ids are distinguishable and round-trip through their hex form`() {
        val a = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 1 })
        val b = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 2 })
        assertNotEquals(a, b)
        assertEquals(a, PeerCashOutId.of(a.value))
        assertEquals(a, PeerCashOutId.of(a.value.uppercase()))
    }

    @Test
    fun `a malformed cash-out id is rejected rather than coerced`() {
        assertNull(PeerCashOutId.ofOrNull("not-hex"))
        assertNull(PeerCashOutId.ofOrNull("00"))
        assertFailsWith<IllegalArgumentException> { PeerCashOutId.of(ByteArray(BYTES32)) }
    }

    /**
     * The reported bug. Attempt B must not be able to read attempt A's `createDeposit` hash, because
     * resolving B's amount against A's submission resolves the wrong deposit.
     */
    @Test
    fun `a checkpoint carries its own attempt and nothing of another`() {
        val a =
            checkpoint(
                id = idOf(1),
                amount = Usdc6.ofMicros(1_000_000L),
                createDepositTxHash = TxHash.fromHex("0x" + "ab".repeat(BYTES32)),
                blockBeforeCreateDeposit = "1000",
            )
        val b = checkpoint(id = idOf(2), amount = Usdc6.ofMicros(2_000_000L))

        assertNotEquals(a.id, b.id)
        assertNull(b.createDepositTxHash)
        assertNull(b.blockBeforeCreateDeposit)
        assertTrue(a.resumeAction is PeerResumeAction.ResolveSubmittedDeposit)
        assertEquals(PeerResumeAction.FreshStart, b.resumeAction)
    }

    @Test
    fun `an unindexed checkpoint holds funds and an indexed one has already spent them`() {
        val pending = checkpoint(id = idOf(1), blockBeforeCreateDeposit = "1000")
        val indexed =
            checkpoint(
                id = idOf(1),
                blockBeforeCreateDeposit = "1000",
                depositId = PeerDepositId(escrowHex = ESCROW, onchain = "42"),
            )
        assertTrue(pending.holdsUnescrowedFunds)
        assertFalse(indexed.holdsUnescrowedFunds)
    }

    /**
     * The reported bug: a reverted `createDeposit` escrowed nothing, but every failure was read as
     * "the money may have moved", so the amount stayed reserved until the wallet was wiped. Only an
     * outcome nobody can prove keeps the reservation.
     */
    @Test
    fun `only an unproven outcome keeps the amount reserved`() {
        assertTrue(PeerErrorCode.TRANSACTION_FAILED.nothingEscrowed)
        assertTrue(PeerErrorCode.INSUFFICIENT_TOKEN_BALANCE.nothingEscrowed)
        assertTrue(PeerErrorCode.FUNDING_BRIDGE_FAILED.nothingEscrowed)

        assertFalse(PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN.nothingEscrowed)
        assertFalse(PeerErrorCode.TRANSACTION_STATUS_UNKNOWN.nothingEscrowed)
        assertFalse(PeerErrorCode.DEPOSIT_RESOLUTION_FAILED.nothingEscrowed)
        assertFalse(PeerErrorCode.INDEXER_UNAVAILABLE.nothingEscrowed)
    }

    @Test
    fun `spendable subtracts what earlier attempts already promised`() {
        val spendable =
            PeerSpendable.Ready(
                baseBalance = Usdc6.ofMicros(2_000_000L),
                committed = Usdc6.ofMicros(1_600_000L),
            )
        assertEquals(Usdc6.ofMicros(400_000L), spendable.available)
        assertTrue(spendable.covers(Usdc6.ofMicros(400_000L)))
        assertFalse(spendable.covers(Usdc6.ofMicros(400_001L)))
        assertTrue(spendable.hasCommitment)
    }

    @Test
    fun `spendable never reports a negative amount when commitments exceed the balance`() {
        val spendable =
            PeerSpendable.Ready(
                baseBalance = Usdc6.ofMicros(1_000_000L),
                committed = Usdc6.ofMicros(3_000_000L),
            )
        assertEquals(Usdc6.ZERO, spendable.available)
        assertFalse(spendable.covers(Usdc6.ofMicros(1L)))
    }

    @Test
    fun `an empty balance covers nothing`() {
        val spendable = PeerSpendable.Ready(baseBalance = Usdc6.ZERO, committed = Usdc6.ZERO)
        assertEquals(Usdc6.ZERO, spendable.available)
        assertFalse(spendable.hasCommitment)
        assertFalse(spendable.covers(Usdc6.ofMicros(1L)))
    }

    @Test
    fun `a selection keeps its primary through toggling and refuses to empty itself`() {
        val selection = PeerCurrencySelection.of(listOf(PeerCurrency.EUR, PeerCurrency.GBP, PeerCurrency.USD))
        assertEquals(PeerCurrency.EUR, selection.primary)
        assertEquals(listOf(PeerCurrency.EUR, PeerCurrency.GBP, PeerCurrency.USD), selection.all)

        val withoutGbp = selection.toggle(PeerCurrency.GBP)
        assertEquals(PeerCurrency.EUR, withoutGbp.primary)
        assertFalse(PeerCurrency.GBP in withoutGbp)

        // Removing the primary promotes rather than leaving the selection headless.
        val promoted = selection.toggle(PeerCurrency.EUR)
        assertEquals(PeerCurrency.GBP, promoted.primary)
        assertFalse(PeerCurrency.EUR in promoted)

        val only = PeerCurrencySelection.of(listOf(PeerCurrency.USD))
        assertEquals(only, only.toggle(PeerCurrency.USD))
    }

    @Test
    fun `a selection cannot repeat its primary`() {
        assertFailsWith<IllegalArgumentException> {
            PeerCurrencySelection(primary = PeerCurrency.EUR, additional = listOf(PeerCurrency.EUR))
        }
        assertFailsWith<IllegalArgumentException> { PeerCurrencySelection.of(emptyList()) }
    }

    /** A quote that carries its own currency cannot be rendered under a different one. */
    @Test
    fun `a rate quote is inseparable from its currency`() {
        val quote =
            PeerRateQuote(
                currency = PeerCurrency.GBP,
                fiatPerUsdc = BigDecimal("0.79"),
                readAtSeconds = 1L,
            )
        assertEquals(PeerCurrency.GBP, quote.currency)
        assertNotEquals(PeerCurrency.EUR, quote.currency)
    }

    private fun idOf(seed: Byte) = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { seed })

    private fun checkpoint(
        id: PeerCashOutId,
        amount: Usdc6 = Usdc6.ofMicros(1_000_000L),
        payeeHash: String = payeeHashHex,
        createDepositTxHash: TxHash? = null,
        blockBeforeCreateDeposit: String? = null,
        depositId: PeerDepositId? = null,
    ) = PeerCashOutCheckpoint(
        id = id,
        platform = PeerPlatform.REVOLUT,
        currencies = listOf(PeerCurrency.EUR),
        payeeHashHex = payeeHash,
        amountMicroDecimal = amount.micros.toString(),
        createDepositTxHash = createDepositTxHash,
        blockBeforeCreateDeposit = blockBeforeCreateDeposit,
        depositId = depositId,
        createdAtMillis = 0L,
    )

    private companion object {
        const val BYTES32 = 32
        const val ESCROW = "0x777777779d229cdF3110e9de47943791c26300Ef"
    }
}
