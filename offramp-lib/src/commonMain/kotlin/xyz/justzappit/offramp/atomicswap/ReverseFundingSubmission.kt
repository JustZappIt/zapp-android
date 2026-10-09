// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Submits outside the driver lock, then merges the returned hash with any intervening cancellation. */
internal class ReverseFundingSubmission(
    private val funding: ReverseSwapFunding,
    private val store: ReverseSwapStore,
    private val lock: Mutex,
) {
    suspend fun submit(
        record: ReverseSwapRecord,
        transaction: ReverseFundingTransaction,
    ) {
        val request = transaction.request
        check(request.swapId == record.swapId && request.chainId == record.deployment.chainId) {
            "the saved funding request is for another swap"
        }
        val txId =
            try {
                funding.submit(transaction)
            } catch (e: AtomicSwapHttpException.Refused) {
                if (e.code == SwapErrorCode.REJECTED && transaction.txId == null) drop(record, transaction)
                throw e
            }
        lock.withLock {
            val current =
                store.active()?.takeIf { it.index == record.index && it.funding == transaction } ?: return@withLock
            val phase =
                if (current.phase == ReversePhase.SENDING_USDC) ReversePhase.CONFIRMING_ESCROW else current.phase
            val submitted = transaction.copy(txId = txId ?: transaction.txId)
            store.keep(current, current.copy(funding = submitted, phase = phase))
        }
    }

    /** After the relayer refused [transaction]: if it asks more now, the cost at its fee shows, to pay or cancel. */
    suspend fun reprice(
        record: ReverseSwapRecord,
        transaction: ReverseFundingTransaction,
        refusal: AtomicSwapHttpException.Refused,
    ): Nothing {
        val cost =
            store
                .active()
                ?.takeIf { it.isDropped(record) }
                ?.let { funding.cost(it.quote.terms.amount) }
                ?.takeIf { it.broadcasterFee > transaction.cost.broadcasterFee }
                ?: throw refusal
        lock.withLock { store.active()?.takeIf { it.isDropped(record) }?.let { store.save(it.copy(cost = cost)) } }
        throw AtomicSwapBlockedException(AtomicSwapBlock.FUNDING_COST_CHANGED, "the relayer asks a higher fee")
    }

    private fun ReverseSwapRecord.isDropped(record: ReverseSwapRecord) =
        index == record.index && funding == null && !cancelRequested

    // A funding the relayer refused was never sent: it waits for the user to pay again.
    private suspend fun drop(
        record: ReverseSwapRecord,
        transaction: ReverseFundingTransaction,
    ) = lock.withLock {
        val current =
            store.active()?.takeIf { it.index == record.index && it.funding == transaction } ?: return@withLock
        val phase = if (current.cancelRequested) current.phase else ReversePhase.AWAITING_FUNDING
        store.save(current.copy(funding = null, phase = phase))
    }
}
