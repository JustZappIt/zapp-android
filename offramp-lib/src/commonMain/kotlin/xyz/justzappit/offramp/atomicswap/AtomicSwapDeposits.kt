// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

/** A forward swap's Zcash side: the deposit, kept until it is mined, and taken home after a refund. */
internal class AtomicSwapDeposits(
    private val deployment: SwapDeployment,
    private val terms: ZcashDepositTerms,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
    private val zcash: AtomicSwapZcash,
    private val store: AtomicSwapStore,
    private val ending: AtomicSwapEnding,
) {
    /** Sends the deposit again until it's mined; pays anew only once the last is proven expired, in time for t0. */
    suspend fun ensurePaid(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): NothingSentCause? {
        val address = keys.depositAddress(record.index, swap.makerShare)
        val unpayable = unpayable(swap)
        var current = withFound(record, address)
        val deposit =
            keepSending(
                kept = current.deposit.transaction,
                status = zcash::depositStatus,
                build = {
                    if (unpayable == null) {
                        val prepared = zcash.prepareDeposit(address, current.quote.depositZat, current.maxTotalZat)
                        onActivity(AtomicSwapActivity.DEPOSITING)
                        current = current.copy(deposit = SwapDeposit.Started).also { store.save(it) }
                        prepared.create()
                    } else {
                        null
                    }
                },
                keep = { kept -> current = current.copy(deposit = kept.asDeposit()).also { store.save(it) } },
                // A deposit the maker is calling off is left to be mined or expire, not pushed.
                send = { if (swap.refundLockUntil == 0L) zcash.submit(it) },
            )
        return unpayable.takeIf { deposit == null }
    }

    /** A refunded swap: a mined deposit is swept home, and one that may still be mined is waited for. */
    suspend fun refunded(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep {
        val current = withFound(record, keys.depositAddress(record.index, swap.makerShare))
        return when (current.deposit.transaction?.let { zcash.depositStatus(it) }) {
            is ZcashTransactionStatus.Mined -> {
                sweep(current, swap, onActivity)
            }

            ZcashTransactionStatus.Unmined, ZcashTransactionStatus.Unknown -> {
                AtomicSwapStep.Waiting(AtomicSwapWait.DEPOSIT_UNSETTLED)
            }

            ZcashTransactionStatus.Expired, null -> {
                ending.finish(current, AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_CANCELLED))
            }
        }
    }

    // The account is forgotten only once the sweep has the confirmations a deposit gets.
    private suspend fun sweep(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep {
        var current = record
        val sweep =
            keepSending(
                kept = current.sweep,
                status = { zcash.sweepStatus(current.index, swap.makerShare, it) },
                build = {
                    onActivity(AtomicSwapActivity.SWEEPING)
                    zcash.prepareSweep(current.index, swap.makerShare, swap.secret, current.zcashHeight)
                },
                keep = { kept -> current = current.copy(sweep = kept).also { store.save(it) } },
                send = zcash::submit,
            )
        val confirmations = (sweep?.second as? ZcashTransactionStatus.Mined)?.confirmations ?: 0
        if (sweep == null || confirmations < deployment.zcashConfirmations) {
            return AtomicSwapStep.Waiting(AtomicSwapWait.REFUNDING)
        }
        val finished = ending.finish(current, AtomicSwapOutcome.Refunded(sweep.first.txId, refundCause(swap)))
        zcash.forgetDepositAccount(current.index, swap.makerShare)
        return finished
    }

    /** Why no deposit may be paid now, or null when one may. Until t0 an unresponsive maker holds it. */
    private suspend fun unpayable(swap: OnChainSwap): NothingSentCause? {
        val now = chain.now()
        return when {
            swap.refundLockUntil != 0L -> NothingSentCause.MAKER_CANCELLED
            swap.t0 > now + MAX_SECONDS_TO_T0 -> NothingSentCause.MISMATCH
            swap.t0 < now + terms.minSecondsToT0 -> NothingSentCause.DEPOSIT_WINDOW_MISSED
            else -> null
        }
    }

    /** [record] with the deposit an interruption cut short, found again in the wallet's history. */
    private suspend fun withFound(
        record: AtomicSwapRecord,
        address: String
    ): AtomicSwapRecord {
        if (record.deposit.transaction != null || record.deposit == SwapDeposit.NotStarted) return record
        return record
            .copy(deposit = zcash.findDeposit(address).asDeposit())
            .also { if (it != record) store.save(it) }
    }

    // A refund lock taken before t0 is the maker calling it off; one taken later waited out the user.
    private suspend fun refundCause(swap: OnChainSwap): RefundCause =
        if (swap.refundLockUntil - chain.lockDuration() < swap.t0) {
            RefundCause.MAKER_CANCELLED
        } else {
            RefundCause.NOT_CLAIMED_IN_TIME
        }

    private companion object {
        const val MAX_SECONDS_TO_T0 = 2 * 60 * 60L

        // A started deposit with no transaction known; only one that never started is NotStarted.
        fun ZcashTransaction?.asDeposit(): SwapDeposit = this?.let(SwapDeposit::Kept) ?: SwapDeposit.Started
    }
}
