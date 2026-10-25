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
    private val nowSeconds: () -> Long,
) {
    /**
     * Sends the deposit again until it's mined, while the swap is open; pays anew only once the last is proven
     * expired, inside the deposit's window, judged on [read]: the swap as of the newest block, and that block's time.
     */
    suspend fun ensurePaid(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        read: SwapRead,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): NothingSentCause? {
        val address = keys.depositAddress(record.index, swap.makerShare)
        val unpayable = unpayable(record, swap, read)
        val isOpen = read.swap?.stage == SwapStage.OPEN
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
                // Sent again only while the swap is open: one the maker called off is left to be mined or expire.
                send = { if (isOpen) zcash.submit(it) },
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
        val finished = ending.finish(current, AtomicSwapOutcome.Refunded(sweep.first.txId, refundCause(current, swap)))
        zcash.forgetDepositAccount(current.index, swap.makerShare)
        return finished
    }

    /**
     * Why no deposit may be paid now, or null when one may: only into a swap still open on the newest block, with `t0`
     * at most two hours out, as until then a maker gone quiet holds the deposit; with `t1` leaving time to claim from
     * `t0`; within the deposit's window from the accept; and with time to confirm before `t0`, by the later of the
     * block's time and this device's clock. The maker would cancel a deposit later than that, and the ZEC would only
     * come back, less two Zcash fees.
     */
    private fun unpayable(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        read: SwapRead,
    ): NothingSentCause? {
        val head = read.head.time
        val deviceNow = nowSeconds()
        val stage =
            read.swap?.stage
                ?: throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "the newest block shows no swap")
        return when {
            stage == SwapStage.REFUNDED -> NothingSentCause.MAKER_CANCELLED
            stage != SwapStage.OPEN || !swap.leavesTimeToClaim() -> NothingSentCause.MISMATCH
            swap.t0 > head + MAX_SECONDS_TO_T0 -> NothingSentCause.MISMATCH
            deviceNow - record.acceptedAt > terms.depositWindowSeconds -> NothingSentCause.DEPOSIT_WINDOW_MISSED
            swap.t0 < maxOf(head, deviceNow) + terms.minSecondsToT0 -> NothingSentCause.DEPOSIT_WINDOW_MISSED
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

    // The maker refunds a forward swap only before t0, calling it off, or from t1, once it went unclaimed: a refund
    // seen before t1 is a cancel, and one seen later is told by the time of its block.
    private suspend fun refundCause(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
    ): RefundCause {
        val isCancel =
            chain.now() < swap.t1 || (chain.refundedAt(record.swapId, record.acceptedAt) ?: swap.t1) < swap.t1
        return if (isCancel) RefundCause.MAKER_CANCELLED else RefundCause.NOT_CLAIMED_IN_TIME
    }

    private companion object {
        const val MAX_SECONDS_TO_T0 = 2 * 60 * 60L

        // Until t1 a maker gone quiet holds the deposit: past this after t0, the swap holds it too long.
        const val MAX_T0_TO_T1_SECONDS = 2 * 60 * 60L

        // From t0 a swap no one made ready is the user's to claim until t1 with the claim's margin to spare.
        fun OnChainSwap.leavesTimeToClaim() = t1 - t0 in 2 * CLAIM_MARGIN_SECONDS..MAX_T0_TO_T1_SECONDS

        // A started deposit with no transaction known; only one that never started is NotStarted.
        fun ZcashTransaction?.asDeposit(): SwapDeposit = this?.let(SwapDeposit::Kept) ?: SwapDeposit.Started
    }
}
