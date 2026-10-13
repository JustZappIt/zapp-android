// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SweepProposalTest {
    @Test
    fun `a sweep pays the fee of every note it spends and leaves no change`() =
        runTest {
            for ((notes, fee) in listOf(1 to 10_000L, 2 to 10_000L, 3 to 15_000L, 5 to 25_000L)) {
                val wallet = Wallet(List(notes) { 60_000L + it })

                val sweep = proposeSweep(wallet.balance, wallet::propose)

                assertEquals(fee, sweep.feeZat, "$notes notes")
                assertEquals(wallet.balance - fee, sweep.proposal, "$notes notes")
                assertEquals(1, sweep.transactions)
            }
        }

    @Test
    fun `dust sent to the joint account neither blocks the sweep nor is paid for`() =
        runTest {
            val wallet = Wallet(listOf(202_021L, 1L, 1L, 5_000L))

            val sweep = proposeSweep(wallet.balance, wallet::propose)

            assertEquals(202_021L - 10_000, sweep.proposal)
            assertEquals(10_000, sweep.feeZat)
        }

    @Test
    fun `a balance a sweep can't pay for is refused`() =
        runTest {
            val wallet = Wallet(listOf(9_000L))

            assertFailsWith<IllegalStateException> { proposeSweep(wallet.balance, wallet::propose) }
        }

    /**
     * The SDK's behavior as the sweep meets it: notes worth no more than the marginal fee are left
     * out of the balance and never spent, the oldest notes go first, and ZIP 317 charges per action.
     */
    private class Wallet(
        notes: List<Long>,
    ) {
        private val spendable = notes.filter { it > MARGINAL_FEE }
        val balance = spendable.sum()

        // The oldest notes that cover the amount and their fee, with change only when it isn't dust.
        fun propose(amount: Long): SweepProposal<Long>? =
            spendable.indices.firstNotNullOfOrNull { last ->
                val total = spendable.take(last + 1).sum()
                val exact = fee(last + 1, outputs = 1)
                val change = fee(last + 1, outputs = 2)
                when {
                    total == amount + exact -> SweepProposal(amount, exact, 1)
                    total >= amount + change + MARGINAL_FEE -> SweepProposal(amount, change, 1)
                    else -> null
                }
            }

        private fun fee(
            spent: Int,
            outputs: Int
        ) = MARGINAL_FEE * maxOf(GRACE_ACTIONS, maxOf(spent, outputs))
    }

    private companion object {
        const val MARGINAL_FEE = 5_000L
        const val GRACE_ACTIONS = 2
    }
}
