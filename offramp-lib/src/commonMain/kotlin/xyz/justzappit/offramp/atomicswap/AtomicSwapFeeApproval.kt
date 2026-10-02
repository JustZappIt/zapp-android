// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.offramp.p2p.Usdc6

/** Explicit approval for older conversions that did not persist their reviewed payout fee. */
class AtomicSwapFeeApproval internal constructor(
    private val claim: AtomicSwapClaim,
    private val store: AtomicSwapStore,
) {
    /** A quote is not approval: the caller must show it before calling [approve]. */
    suspend fun quote(index: Int): Usdc6 = claim.relayerFee(needsFeeApproval(index).quote.amount)

    suspend fun approve(expected: AtomicSwapRecord, approved: Usdc6) {
        val record = needsFeeApproval(expected.index)
        check(record == expected) { "the conversion changed; review its fee again" }
        check(claim.relayerFee(record.quote.amount) == approved) { "the payout fee changed; review it again" }
        check(needsFeeApproval(expected.index) == record) { "the conversion changed during approval" }
        store.save(record.copy(relayerFee = approved, receives = payoutAfterFees(record.quote.amount, approved)))
    }

    private suspend fun needsFeeApproval(index: Int): AtomicSwapRecord =
        checkNotNull(
            store.active()?.takeIf {
                it.index == index && !it.finished && it.relayerFee == null && it.payout == null
            }
        ) { "this conversion does not need a payout fee approval" }
}
