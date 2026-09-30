// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord

/** Looks up, on the chain, the payout of paid swaps kept without one. */
internal class AtomicSwapPayouts(
    private val sessions: AtomicSwapSessions,
    private val store: AtomicSwapRecords,
    private val scope: CoroutineScope,
) {
    private var lookup: Job? = null

    fun findMissing(history: suspend () -> List<AtomicSwapRecord>) {
        synchronized(this) {
            if (lookup?.isActive == true) return
            lookup =
                scope.launch {
                    history()
                        .filter { it.outcome == AtomicSwapOutcome.Paid && it.payoutTx == null }
                        .forEach { find(it) }
                }
        }
    }

    suspend fun cancel() {
        synchronized(this) { lookup.also { lookup = null } }?.cancelAndJoin()
    }

    private suspend fun find(record: AtomicSwapRecord) =
        catchingSwapFailures(
            onFailure = { e, _ -> Twig.info { "Atomic swap: no payout found for ${record.index}, ${e.message}" } },
        ) {
            val tx = sessions.chain(record).payoutTx(record.swapId, record.end?.at ?: record.acceptedAt)
            if (tx != null) store.update(record.index) { it.copy(payoutTx = tx) }
        }
}
