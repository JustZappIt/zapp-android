// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.HttpStatusCode
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * A forward claim handed to the relayer once the deposit is out, for it to send the moment the claim's window opens,
 * so the user may leave the app (zecSwap's `POST /v1/claim/hold`). [sign] signs and keeps the claim's payout, at the
 * fee given or as the claim would.
 */
internal class AtomicSwapHandOver(
    private val deployment: SwapDeployment,
    private val relayer: SwapRelayer,
    private val keys: AtomicSwapKeys,
    private val store: AtomicSwapStore,
    private val sign: suspend (AtomicSwapRecord, OnChainSwap, Usdc6?) -> SwapPayout,
) {
    /**
     * Hands the claim over: once a swap, unless a post got no answer, which goes again as it was kept. It never holds
     * up the claim, which the app makes in the window itself. The record as kept.
     */
    suspend fun handOver(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
    ): AtomicSwapRecord {
        val current = store.active()?.takeIf { it.index == record.index && !it.finished } ?: return record
        val isDue =
            current.deposit is SwapDeposit.Kept &&
                current.claimPostedAt == null &&
                (current.claimHold == null || current.claimHold == ClaimHold.POSTED)
        if (isDue) {
            try {
                post(current, swap)
            } catch (_: AtomicSwapHttpException) {
                // No answer, or one that doesn't read: posted again as kept at the next step.
            } catch (_: AtomicSwapBlockedException) {
                // Nothing to hand over now, such as a fee that leaves nothing: the claim meets it in its window.
            }
        }
        return store.active()?.takeIf { it.index == record.index } ?: current
    }

    /**
     * Keeps the payout and the hand-over as posted, then posts it; one posted before goes again with the payout kept.
     * One the relayer rejects is signed again, once, at a higher fee it asks now. Any other refusal, or a relayer
     * without the route, declines it, and the app claims in the window as it always did.
     */
    private suspend fun post(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
    ) {
        val payout = record.payout?.takeIf { record.claimHold == ClaimHold.POSTED } ?: sign(record, swap, null)
        keep(record, ClaimHold.POSTED)
        val refused = hold(record, swap, payout)
        val fee = refused?.takeIf { it.code == SwapErrorCode.REJECTED }?.let { higherFee(swap, payout) }
        val isHeld = refused == null || (fee != null && hold(record, swap, sign(record, swap, fee)) == null)
        keep(record, if (isHeld) ClaimHold.HELD else ClaimHold.DECLINED)
    }

    /**
     * Posts the claim, `z` and [payout] with it, for the relayer to hold until the claim's window opens: null once
     * held, or for a swap claimed already; else its refusal, a `4xx` that holds nothing. Anything else is thrown,
     * unanswered.
     *
     * It hands the relayer the user's share long before the claim reveals it, the trade the owner accepted (zecSwap's
     * `docs/deployment.md`, Held claims). The share alone can't spend the deposit, as the joint account's `nk` and
     * `rivk` go to the maker only, but handed to the maker it would let the maker both refund the swap and sweep the
     * deposit.
     */
    private suspend fun hold(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        payout: SwapPayout,
    ): AtomicSwapHttpException.Refused? {
        val reveal = SwapReveal(record.swapId, keys.claimSecret(record.index).hex(), payout)
        return try {
            relayer.holdClaim(reveal, swap.terms, record.ticket)
            null
        } catch (e: AtomicSwapHttpException.Refused) {
            if (e.status in CLIENT_ERRORS) e else throw e
        }
    }

    // The relayer's fee now, where it asks more than [payout] pays and leaves something of the swap.
    private suspend fun higherFee(
        swap: OnChainSwap,
        payout: SwapPayout,
    ): Usdc6? = relayer.terms().checkedFee(deployment).takeIf { it > payout.fee && it < swap.amount }

    // Kept on the record as stored, which signing the payout may have changed.
    private suspend fun keep(
        record: AtomicSwapRecord,
        hold: ClaimHold,
    ) {
        val current = checkNotNull(store.active()?.takeIf { it.index == record.index && !it.finished })
        if (current.claimHold != hold) store.save(current.copy(claimHold = hold))
    }

    private companion object {
        // A 4xx: the relayer turned the request down, and holds nothing.
        val CLIENT_ERRORS = HttpStatusCode.BadRequest.value until HttpStatusCode.InternalServerError.value
    }
}
