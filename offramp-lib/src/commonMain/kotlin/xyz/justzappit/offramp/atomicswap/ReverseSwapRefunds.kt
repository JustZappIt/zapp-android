// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

/** Getting a reverse swap's dollars back: the refund in its window, its payout, and a rescue. */
internal class ReverseSwapRefunds(
    private val deployment: SwapDeployment,
    private val relayer: SwapRelayer,
    private val chain: ReverseSwapChain,
    private val keys: AtomicSwapKeys,
    private val reverseKeys: ReverseSwapKeys,
    private val store: ReverseSwapStore,
    private val verifier: ReverseSwapVerifier,
    private val ending: ReverseSwapEnding,
    private val nowSeconds: () -> Long,
) {
    /** Called off, refunded, or past the deadline of the stage its escrow is in. */
    fun isDue(
        record: ReverseSwapRecord,
        observed: ReverseChainState
    ): Boolean =
        record.cancelRequested ||
            when (observed.swap?.stage) {
                SwapStage.REFUNDED -> true
                SwapStage.READY -> observed.now >= record.quote.refundAfter
                SwapStage.OPEN -> observed.now >= record.quote.readyDeadline
                else -> false
            }

    /** Whether a refund Railgun sent back to its vault holds more than a rescue's relayer fee. */
    suspend fun canRescue(record: ReverseSwapRecord): Boolean =
        record.phase == ReversePhase.REFUNDED && rescueTerms(record) != null

    /** One try at shielding a returned refund again. The swap stays finished, so it never holds up another. */
    suspend fun rescue(
        record: ReverseSwapRecord,
        outbox: ReverseOutbox,
    ) {
        check(record.phase == ReversePhase.REFUNDED) { "only a refunded conversion is rescued" }
        val nonce = chain.rescueNonce(record.swapId)
        val terms = rescueTerms(record) ?: return
        val now = freshHead(verifier.state(record).now, nowSeconds())
        check(now <= Long.MAX_VALUE - SIGNATURE_TTL_SECONDS) { "invalid chain time" }
        val deadline = now + SIGNATURE_TTL_SECONDS
        val rescue =
            SwapRescue(
                record.swapId,
                keys.payoutNote(record.index).wire(),
                terms.fee,
                nonce,
                deadline,
                reverseKeys.signRescue(record, terms, nonce, deadline).hex(),
            )
        store.update(record.copy(rescue = rescue))
        outbox.send { relayer.rescue(rescue, record.terms, record.ticket) }
    }

    suspend fun advance(
        record: ReverseSwapRecord,
        observed: ReverseChainState,
        outbox: ReverseOutbox,
    ) {
        val escrow = checkNotNull(observed.swap) { "a refund needs its escrow" }
        when {
            escrow.stage == SwapStage.REFUNDED && escrow.paidOut -> {
                ending.finish(record, ReversePhase.REFUNDED)
            }

            escrow.stage == SwapStage.REFUNDED -> {
                sendPayout(record, ReversePhase.REFUND_PAYOUT, outbox) {
                    relayer.refundPayout(it, record.terms, record.ticket)
                }
            }

            escrow.stage == SwapStage.READY && observed.now < escrow.t1 -> {
                store.keep(record, record.copy(phase = ReversePhase.REFUND_WAIT))
            }

            else -> {
                reveal(record, outbox)
            }
        }
    }

    // Of every reveal, this is the one that can lose money: a refund landing behind a ready reverts with the user's
    // share public, and the maker could take both sides. So it goes only where its window lets it on a fresh consistent
    // read, judged again on another right before it leaves, never on a relayer's answer, an old read or a lagging head.
    // It's kept as posted before it goes, and no ready is ever signed after.
    private suspend fun reveal(
        record: ReverseSwapRecord,
        outbox: ReverseOutbox,
    ) {
        if (refusal(record) != null) {
            store.keep(record, record.copy(phase = ReversePhase.REFUND_WAIT))
            return
        }
        sendPayout(record, ReversePhase.REFUNDING, outbox, posted = record.refundPostedAt ?: nowSeconds()) {
            val reveal = SwapReveal(record.swapId, keys.claimSecret(record.index).hex(), it)
            if (refusal(record) == null) relayer.refund(reveal, record.terms, record.ticket)
        }
    }

    /**
     * Why the refund can't go now, on a fresh consistent read; null when it may. An open escrow is the user's to refund
     * once the chain is past the deadline of the ready we signed, or at `t0`, and a ready one from `t1`.
     */
    private suspend fun refusal(record: ReverseSwapRecord): Refusal? {
        val read = verifier.latest(record)
        freshHead(read.head.time, nowSeconds())
        val escrow = read.swap
        val windows = deployment.windows
        return if (escrow == null || windows == null) {
            Refusal.Shut
        } else {
            windows.reverseRefund(escrow.stage, escrow.t0, escrow.t1, read.head, record.ready?.deadline, false)
        }
    }

    /**
     * Signs the refund's payout when it goes out, at the relayer's fee then, and keeps it with the record first,
     * with when the refund was [posted] where one goes.
     */
    private suspend fun sendPayout(
        record: ReverseSwapRecord,
        phase: ReversePhase,
        outbox: ReverseOutbox,
        posted: Long? = record.refundPostedAt,
        send: suspend (SwapPayout) -> Unit,
    ) {
        val terms = verifier.relayerTerms(record)
        val payout =
            record.payout?.takeIf { it.fee == terms.fee }
                ?: payout(record, terms, reverseKeys.signPayout(record, terms))
        val expectedNote = keys.payoutNote(record.index).wire()
        check(payout.swapId == record.swapId && payout.note == expectedNote) {
            "the saved refund payout is for another swap"
        }
        store.keep(record, record.copy(payout = payout, phase = phase, refundPostedAt = posted))
        outbox.send { send(payout) }
    }

    private suspend fun rescueTerms(record: ReverseSwapRecord): RelayerTerms? {
        val refunded = verifier.state(record).swap?.takeIf { it.stage == SwapStage.REFUNDED && it.paidOut }
        return refunded?.let { verifier.relayerTerms(record) }?.takeIf { chain.vaultBalance(record.swapId) > it.fee }
    }

    private suspend fun payout(
        record: ReverseSwapRecord,
        terms: RelayerTerms,
        signature: ByteArray,
    ) = SwapPayout(record.swapId, keys.payoutNote(record.index).wire(), terms.fee, signature.hex())
}
