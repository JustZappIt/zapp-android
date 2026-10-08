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
        val txId = funding.submit(transaction)
        lock.withLock {
            val current =
                store.active()?.takeIf { it.index == record.index && it.funding == transaction } ?: return@withLock
            val phase =
                if (current.phase == ReversePhase.SENDING_USDC) ReversePhase.CONFIRMING_ESCROW else current.phase
            val submitted = transaction.copy(txId = txId ?: transaction.txId)
            store.keep(current, current.copy(funding = submitted, phase = phase))
        }
    }
}
