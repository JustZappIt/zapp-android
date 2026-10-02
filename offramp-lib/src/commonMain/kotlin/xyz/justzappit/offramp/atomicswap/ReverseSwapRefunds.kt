// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

/** Getting a reverse swap's dollars back: the refund lock, the reveal under it, the payout, and a rescue. */
internal class ReverseSwapRefunds(
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
        record.phase == ReversePhase.REFUNDED && chain.rescueNonce(record.swapId) != null && rescueTerms(record) != null

    /** One try at shielding a returned refund again. The swap stays finished, so it never holds up another. */
    suspend fun rescue(
        record: ReverseSwapRecord,
        outbox: ReverseOutbox,
    ) {
        check(record.phase == ReversePhase.REFUNDED) { "only a refunded conversion is rescued" }
        val nonce = checkNotNull(chain.rescueNonce(record.swapId)) { "this deployment cannot consume rescue approvals" }
        val terms = rescueTerms(record) ?: return
        val now = freshHead(verifier.state(record).now, nowSeconds())
        check(now <= Long.MAX_VALUE - SIGNATURE_TTL_SECONDS) { "invalid chain time" }
        val deadline = now + SIGNATURE_TTL_SECONDS
        val rescue =
            SwapRescue(
                record.swapId,
                keys.payoutNote(record.index, record.railgunKeys).wire(),
                terms.fee,
                nonce,
                deadline,
                reverseKeys.signRescue(record, terms, nonce, deadline).hex(),
            )
        store.update(record.copy(rescue = rescue))
        outbox.send { relayer.rescue(rescue) }
    }

    suspend fun advance(
        record: ReverseSwapRecord,
        observed: ReverseChainState,
        outbox: ReverseOutbox,
    ) {
        val escrow = checkNotNull(observed.swap) { "a refund needs its escrow" }
        when {
            escrow.stage == SwapStage.REFUNDED && escrow.paidOut -> {
                ending.finish(record.copy(rescuePending = false), ReversePhase.REFUNDED)
            }

            escrow.stage == SwapStage.REFUNDED -> {
                sendPayout(record, ReversePhase.REFUND_PAYOUT, outbox) { relayer.refundPayout(it) }
            }

            escrow.stage == SwapStage.READY && observed.now < escrow.t1 -> {
                store.keep(record, record.copy(phase = ReversePhase.REFUND_WAIT))
            }

            escrow.refundLockUntil <= observed.now -> {
                lockRefund(record, escrow, observed, outbox)
            }

            else -> {
                reveal(record, outbox)
            }
        }
    }

    // A claim lock held, or the maker's turn after a refund lock of ours lapsed, keeps the refund waiting.
    private suspend fun lockRefund(
        record: ReverseSwapRecord,
        escrow: OnChainSwap,
        observed: ReverseChainState,
        outbox: ReverseOutbox,
    ) {
        if (!mayTakeLock(escrow.refundLockUntil, escrow.claimLockUntil, observed.now, observed.lockDuration)) return
        check(SIGNATURE_TTL_SECONDS < observed.lockDuration) { "the lock is too short to sign for" }
        check(observed.now <= Long.MAX_VALUE - SIGNATURE_TTL_SECONDS) { "invalid chain time" }
        val deadline = observed.now + SIGNATURE_TTL_SECONDS
        val authorization =
            record.refundLock?.takeIf { it.deadline >= observed.now }
                ?: SwapAuthorization(record.swapId, deadline, reverseKeys.signLockRefund(record, deadline).hex())
        store.keep(record, record.copy(refundLock = authorization, phase = ReversePhase.REFUNDING))
        outbox.send { relayer.lockRefund(authorization) }
    }

    // Never sends the secret on the strength of a relayer response, an old lock observation or a lagging head.
    private suspend fun reveal(
        record: ReverseSwapRecord,
        outbox: ReverseOutbox,
    ) {
        val fresh = verifier.state(record)
        val escrow = fresh.swap?.takeIf { it.stage == SwapStage.OPEN || it.stage == SwapStage.READY } ?: return
        val observedNow = freshHead(fresh.now, nowSeconds())
        val safe =
            escrow.refundLockUntil > observedNow &&
                escrow.refundLockUntil - observedNow > REVEAL_MARGIN_SECONDS
        if (!safe) return
        sendPayout(record, ReversePhase.REFUNDING, outbox) {
            val reveal = SwapReveal(record.swapId, keys.claimSecret(record.index).hex(), it)
            val current = verifier.state(record)
            val locked =
                current.swap?.takeIf { swap ->
                    swap.stage == SwapStage.OPEN || swap.stage == SwapStage.READY
                }
            val now = freshHead(current.now, nowSeconds())
            if (locked != null && locked.refundLockUntil > now &&
                locked.refundLockUntil - now > REVEAL_MARGIN_SECONDS
            ) {
                relayer.refund(reveal)
            }
        }
    }

    /** Signs the refund's payout only when it goes out, and keeps it with the record first. */
    private suspend fun sendPayout(
        record: ReverseSwapRecord,
        phase: ReversePhase,
        outbox: ReverseOutbox,
        send: suspend (SwapPayout) -> Unit,
    ) {
        val terms = verifier.relayerTerms(record)
        val payout = record.payout ?: payout(record, terms, reverseKeys.signPayout(record, terms))
        val expectedNote = keys.payoutNote(record.index, record.railgunKeys).wire()
        check(payout.swapId == record.swapId && payout.note == expectedNote) {
            "the saved refund payout is for another swap"
        }
        store.keep(record, record.copy(payout = payout, phase = phase))
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
    ) = SwapPayout(record.swapId, keys.payoutNote(record.index, record.railgunKeys).wire(), terms.fee, signature.hex())
}
