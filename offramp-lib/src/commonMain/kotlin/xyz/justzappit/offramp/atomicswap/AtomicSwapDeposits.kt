// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.util.hexToBytes

internal enum class DepositResult { PAID, UNSETTLED, MISMATCH, TOO_LATE }

/** The Zcash side: the deposit, paid once into a swap that matches its quote, and taken home after a refund. */
internal class AtomicSwapDeposits(
    private val config: AtomicSwapConfig,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
    private val zcash: AtomicSwapZcash,
    private val store: AtomicSwapStore,
) {
    /**
     * Pays the deposit to the account the on-chain shares make, only if every term matches the quote
     * and there's time to confirm before t0, and never twice: a deposit cut short is looked up in the
     * wallet's history first.
     */
    suspend fun pay(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): DepositResult {
        val address = keys.depositAddress(record.index, swap.makerShare)
        val earlier = if (record.depositAttempted) zcash.findPayment(address) else null
        val now = chain.now()
        val tooLate = swap.t0 < now + config.minSecondsToT0
        return when {
            earlier != null -> {
                store.save(record.copy(depositTxId = earlier))
                DepositResult.PAID
            }

            !matchesQuote(record, swap, now) -> {
                DepositResult.MISMATCH
            }

            // A deposit started earlier can't be proven absent: the maker's refund settles it.
            tooLate && record.depositAttempted -> {
                DepositResult.UNSETTLED
            }

            tooLate -> {
                DepositResult.TOO_LATE
            }

            else -> {
                onActivity(AtomicSwapActivity.DEPOSITING)
                store.save(record.copy(depositAttempted = true))
                val txId = zcash.pay(address, record.quote.depositZat)
                store.save(record.copy(depositAttempted = true, depositTxId = txId))
                DepositResult.PAID
            }
        }
    }

    suspend fun refund(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapOutcome =
        if (deposited(record, swap)) {
            onActivity(AtomicSwapActivity.SWEEPING)
            val txId = zcash.sweepRefund(record.index, swap.makerShare, swap.secret, record.zcashHeight)
            AtomicSwapOutcome.Refunded(txId, refundCause(swap))
        } else {
            AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_CANCELLED)
        }

    private suspend fun matchesQuote(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        now: Long,
    ): Boolean =
        swap.makerShare.contentEquals(record.quote.makerShare.hexToBytes()) &&
            swap.userShare.contentEquals(keys.userShare(record.index)) &&
            swap.user == keys.authAddress(record.index) &&
            swap.payoutNote.contentEquals(keys.payoutNote(record.index).commitment) &&
            swap.token == config.token &&
            swap.amount.compareTo(BigInteger(record.quote.amount)) == 0 &&
            // Until t0 an unresponsive maker holds the deposit.
            swap.t0 <= now + MAX_SECONDS_TO_T0

    // A refund lock taken before t0 is the maker calling it off; one taken later waited out the user.
    private suspend fun refundCause(swap: OnChainSwap): RefundCause =
        if (swap.refundLockUntil - chain.lockDuration() < swap.t0) {
            RefundCause.MAKER_CANCELLED
        } else {
            RefundCause.NOT_CLAIMED_IN_TIME
        }

    /** A deposit recorded as paid, or one cut short that the wallet's history shows went out. */
    private suspend fun deposited(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): Boolean =
        record.depositTxId != null ||
            (record.depositAttempted && zcash.findPayment(keys.depositAddress(record.index, swap.makerShare)) != null)

    private companion object {
        const val MAX_SECONDS_TO_T0 = 2 * 60 * 60L
    }
}
