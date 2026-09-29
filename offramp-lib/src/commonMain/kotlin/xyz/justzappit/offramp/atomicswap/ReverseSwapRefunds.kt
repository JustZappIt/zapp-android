// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

internal class ReverseSwapRefunds(
    private val api: ReverseSwapApi,
    private val chain: ReverseSwapChain,
    private val keys: AtomicSwapKeys,
    private val reverseKeys: ReverseSwapKeys,
    private val store: ReverseSwapStore,
    private val verifier: ReverseSwapVerifier,
) {
    suspend fun canRescue(record: ReverseSwapRecord): Boolean =
        record.phase == ReversePhase.REFUNDED && !record.rescuePending && rescueTerms(record) != null

    suspend fun rescue(record: ReverseSwapRecord) {
        if (record.rescuePending) {
            advance(record, verifier.state(record))
            return
        }
        val terms = rescueTerms(record) ?: return
        val payout = payout(record, terms, reverseKeys.signRescue(record, terms))
        store.save(record.copy(payout = payout, rescuePending = true, phase = ReversePhase.REFUND_PAYOUT))
        api.rescue(payout)
    }

    private suspend fun rescueTerms(record: ReverseSwapRecord): ReverseRelayerTerms? {
        val state = verifier.state(record)
        if (state.swap?.stage != SwapStage.REFUNDED || !state.swap.paidOut) return null
        val terms = verifiedRelayer(record)
        return terms.takeIf { decimalUnits(chain.vaultBalance(record.swapId)) > decimalUnits(it.fee) }
    }

    @Suppress("ReturnCount")
    suspend fun advance(initial: ReverseSwapRecord, observed: ReverseChainState) {
        var record = initial
        val escrow = checkNotNull(observed.swap)
        if (escrow.stage == SwapStage.REFUNDED && escrow.paidOut) {
            if (record.rescuePending && decimalUnits(chain.vaultBalance(record.swapId)).signum() > 0) {
                api.rescue(checkNotNull(record.payout))
                return
            }
            store.save(record.copy(rescuePending = false, phase = ReversePhase.REFUNDED))
            return
        }
        if (escrow.stage == SwapStage.READY && observed.now < escrow.t1) {
            store.save(record.copy(phase = ReversePhase.REFUND_WAIT))
            return
        }
        val terms = verifiedRelayer(record)
        val payout = payout(record, terms, reverseKeys.signPayout(record, terms))
        record = record.copy(payout = payout)
        store.save(record)
        if (escrow.stage == SwapStage.REFUNDED) {
            store.save(record.copy(phase = ReversePhase.REFUND_PAYOUT))
            api.payout(payout)
            return
        }
        if (escrow.refundLockUntil <= observed.now) {
            if (escrow.claimLockUntil > observed.now ||
                (
                    escrow.refundLockUntil > escrow.claimLockUntil &&
                        observed.now < escrow.refundLockUntil + observed.lockDuration
                )
            ) {
                return
            }
            val deadline = observed.now + ReverseSwapDriver.SIGNATURE_TTL
            check(ReverseSwapDriver.SIGNATURE_TTL < observed.lockDuration)
            val authorization =
                record.refundLock?.takeIf { it.deadline >= observed.now } ?: ReverseAuthorization(
                    record.swapId,
                    deadline,
                    reverseKeys.signLockRefund(record, deadline).hex(),
                )
            store.save(record.copy(refundLock = authorization, phase = ReversePhase.REFUNDING))
            api.lockRefund(authorization)
            return
        }
        // Never send the secret on the strength of a relayer response or an old lock observation.
        val fresh = verifier.state(record)
        check(fresh.swap?.stage in setOf(SwapStage.OPEN, SwapStage.READY))
        if (checkNotNull(fresh.swap).refundLockUntil <= fresh.now + ReverseSwapDriver.REVEAL_MARGIN) return
        store.save(record.copy(phase = ReversePhase.REFUNDING))
        api.refund(ReverseRefund(record.swapId, keys.claimSecret(record.index).hex(), payout))
    }

    private suspend fun payout(
        record: ReverseSwapRecord,
        terms: ReverseRelayerTerms,
        signature: ByteArray,
    ): ReversePayout {
        val note = keys.payoutNote(record.index)
        return ReversePayout(
            record.swapId,
            ReverseNote(note.npk.hex(), note.encryptedBundle.map { it.hex() }, note.shieldKey.hex()),
            terms.fee,
            signature.hex(),
        )
    }

    private suspend fun verifiedRelayer(record: ReverseSwapRecord): ReverseRelayerTerms =
        api.terms().also {
            check(it.chainId == record.deployment.chainId && sameAddress(it.contract, record.deployment.contract))
            check(
                sameAddress(it.relayer, record.deployment.relayer) && !sameAddress(it.relayer, record.quote.terms.maker)
            )
            check(decimalUnits(it.fee) <= decimalUnits(record.deployment.maxRefundFee))
            check(decimalUnits(it.fee) < decimalUnits(record.quote.terms.amount))
        }
}
