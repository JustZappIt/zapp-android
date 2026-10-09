// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.time.Clock

/** Serializes record changes; funding proofs and transaction submission run outside the lock. */
class ReverseSwapDriver(
    private val deployment: SwapDeployment,
    private val maker: SwapMaker,
    private val relayer: SwapRelayer,
    private val chain: ReverseSwapChain,
    private val keys: AtomicSwapKeys,
    private val reverseKeys: ReverseSwapKeys,
    private val zcash: ReverseSwapZcash,
    private val funding: ReverseSwapFunding,
    private val indices: SwapIndices,
    private val forward: AtomicSwapStore,
    private val store: ReverseSwapStore,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val lock = Mutex()

    // A token pays for each accept, so only one is ever in flight.
    private val accepting = Mutex()
    private val records = DeploymentRecords(deployment, store, forward)
    private val verifier = ReverseSwapVerifier(deployment, chain, keys, relayer)
    private val ending = ReverseSwapEnding(store, zcash)
    private val submission = ReverseFundingSubmission(funding, store, lock)
    private val opening = ReverseSwapOpening(maker, chain, submission::submit, store, ending, lock)
    private val receiving = ReverseSwapReceiving(deployment, zcash, store, ending)
    private val refunds = ReverseSwapRefunds(relayer, chain, keys, reverseKeys, store, verifier, ending, nowSeconds)
    private val approvals = ReverseSwapApprovals(records, verifier, reverseKeys, store)
    private val stages = ReverseSwapStages(verifier, opening, receiving, refunds, store, relayer)

    // Checked once: every quote is checked against the deployment as well.
    private var isMakerChecked = false

    /** A verified quote for [requested], kept as a preview under a fresh index. */
    suspend fun quote(requested: Usdc6): ReverseSwapRecord {
        require(requested.micros.signum() > 0) { "a conversion moves something" }
        records.requireNothingUnderWay()
        if (!isMakerChecked) {
            maker.info().requireServing(deployment, reverse = true)
            isMakerChecked = true
        }
        // A refund pays out less the relayer's fee: one it would take all of could never come back.
        relayer.terms().checkedFee(deployment, requested)
        val index = indices.take()
        val user = keys.authAddress(index)
        val note = keys.payoutNote(index).commitment
        val quote = maker.quoteReverse(requested, user, note)
        ReverseSwapVerifier.verifyQuote(quote, deployment, user, note)
        if (quote.terms.amount != requested) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, "the quote is for another amount")
        }
        val acceptance =
            keys.accept(
                index,
                deployment.chainId,
                deployment.contract,
                fixedHex(quote.terms.quoteId, SWAP_WORD_BYTES),
                quote.terms.makerShare,
                fixedHex(quote.terms.makerProof, SWAP_SHARE_BYTES),
            )
        val id = ReverseSwapId.of(user, quote.terms.makerShare)
        requireTimeToGoAhead(quote, chain.read(id, SwapTerms.reverse(quote, acceptance.userShare)).now)
        val record =
            ReverseSwapRecord(
                index = index,
                deployment = deployment,
                quote = quote,
                swapId = id,
                userShare = acceptance.userShare,
                acceptance = acceptance.wire(),
                birthday = zcash.chainHeight(),
                phase = ReversePhase.QUOTED,
                cost = funding.cost(quote.terms.amount),
            )
        lock.withLock {
            records.requireNothingUnderWay()
            store.save(record)
        }
        return record
    }

    /** The user went ahead with the preview: it's under way from here, though nothing is paid yet. */
    suspend fun goAhead(index: Int) =
        lock.withLock {
            val record = records.current(index)
            if (record.phase != ReversePhase.QUOTED) return@withLock
            if (forward.active()?.finished == false) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a conversion to USD is under way")
            }
            requireQuoteHolds(record.quote, verifier.state(record).now)
            store.save(record.copy(phase = ReversePhase.ACCEPTING, acceptedAt = nowSeconds()))
        }

    suspend fun advance(): ReverseSwapRecord? {
        val snapshot = store.active()
        if (snapshot == null || !snapshot.underWay) return snapshot
        val deposit = if (snapshot.phase in AWAITING_DEPOSIT) receiving.lookAtDeposit(snapshot) else null
        locked { outbox ->
            store
                .active()
                ?.takeIf { it.underWay }
                ?.let { stages.advance(it, deposit?.takeIf { _ -> it.index == snapshot.index }, outbox) }
        }
        store.active()?.takeIf { it.isAccepting }?.let { accepted(it.index) }
        // The maker hands the accept's token back once the escrow is funded.
        if (snapshot.funding != null) maker.collectReverseToken(snapshot.swapId)
        return store.active()
    }

    /** Only the first foreground authorization calls this. The transaction is kept before it is first sent. */
    suspend fun fund(index: Int) {
        accepted(index)
        val record = lock.withLock { approvals.fundable(index) }
        if (record.funding != null) {
            advance()
            return
        }
        val transaction = funding.prepare(record, reverseKeys.signOpen(record))
        if (transaction.cost != record.cost) {
            // Nothing is paid; the new cost shows, for the user to pay or cancel.
            lock.withLock { if (records.current(index) == record) store.save(record.copy(cost = transaction.cost)) }
            throw AtomicSwapBlockedException(AtomicSwapBlock.FUNDING_COST_CHANGED, "funding costs other than reviewed")
        }
        lock.withLock {
            check(records.current(index) == record) { "the conversion changed while its payment was being prepared" }
            store.save(record.copy(funding = transaction, phase = ReversePhase.SENDING_USDC))
        }
        try {
            submission.submit(record, transaction)
        } catch (e: AtomicSwapHttpException.Refused) {
            submission.reprice(record, transaction, e)
        }
    }

    /** Only the second foreground authorization calls this; a restart never authorizes `ready`. */
    suspend fun ready(index: Int) {
        val snapshot = records.current(index)
        requireGoingAhead(snapshot)
        if (snapshot.ready != null) {
            advance()
            return
        }
        val estimate = receiving.estimate(snapshot)
        val authorized = lock.withLock { approvals.authorizeReady(index, estimate) }
        relayer.ready(authorized, snapshot.terms)
    }

    suspend fun cancel(index: Int) {
        val isCalledOff =
            lock.withLock {
                val record = records.current(index)
                record.requireRefundPossible()
                if (!record.finished && !record.cancelRequested) {
                    val phase =
                        if (record.phase == ReversePhase.REFUNDING || record.phase == ReversePhase.REFUND_PAYOUT) {
                            record.phase
                        } else {
                            ReversePhase.REFUND_WAIT
                        }
                    store.keep(record, record.copy(cancelRequested = true, phase = phase))
                }
                !record.finished
            }
        if (isCalledOff) {
            val result = runCatching { advance() }
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            records.kept(index).requireRefundPossible()
            result.getOrThrow()
        }
    }

    /** Recovers the refund of conversion [index], whichever conversion is active now. */
    suspend fun rescue(index: Int) = locked { outbox -> refunds.rescue(records.kept(index), outbox) }

    suspend fun canRescue(index: Int): Boolean = refunds.canRescue(records.kept(index))

    /** Runs [step] under the lock, then sends what it kept once the lock is let go. */
    private suspend fun <T> locked(step: suspend (ReverseOutbox) -> T): T {
        val outbox = ReverseOutbox()
        val result = lock.withLock { step(outbox) }
        outbox.flush()
        return result
    }

    /** [index] accepted by the maker, once, and its joint account imported, which holds if done twice. */
    private suspend fun accepted(index: Int) =
        accepting.withLock {
            val snapshot = records.current(index)
            requireGoingAhead(snapshot)
            if (snapshot.account == null) {
                if (!snapshot.makerAccepted) opening.accept(snapshot)
                val account = zcash.importAccount(snapshot)
                lock.withLock { opening.keepAccepted(records.current(index), account) }
            }
        }

    internal companion object {
        // Where the maker's ZEC is expected in the joint account, so each look syncs it.
        val AWAITING_DEPOSIT = setOf(ReversePhase.RECEIVING_ZEC, ReversePhase.AWAITING_READY)

        val ReverseSwapRecord.isAccepting: Boolean
            get() = underWay && account == null && funding == null && !cancelRequested

        fun requireGoingAhead(record: ReverseSwapRecord) =
            check(record.underWay && !record.cancelRequested) { "the conversion isn't waiting on the user" }

        fun requireQuoteHolds(
            quote: ReverseQuote,
            now: Long
        ) {
            if (now >= quote.terms.expiresAt || now >= quote.fundingDeadline) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.QUOTE_EXPIRED, "the quote ran out")
            }
        }

        fun requireTimeToGoAhead(
            quote: ReverseQuote,
            now: Long
        ) {
            requireQuoteHolds(quote, now)
            if (quote.readyDeadline <= now + MIN_SECONDS_TO_T0 || quote.refundAfter > now + MAX_READY_WAIT) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, "the deadlines leave too little time")
            }
        }

        fun requireTimeToFund(
            quote: ReverseQuote,
            now: Long
        ) {
            if (now >= quote.fundingDeadline || quote.readyDeadline <= now + MIN_SECONDS_TO_T0) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.QUOTE_EXPIRED, "too late to fund it")
            }
        }
    }
}

/** A driver's records: only those of its own deployment. */
internal class DeploymentRecords(
    private val deployment: SwapDeployment,
    private val store: ReverseSwapStore,
    private val forward: AtomicSwapStore,
) {
    suspend fun requireNothingUnderWay() {
        if (store.active()?.underWay == true || forward.active()?.finished == false) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a conversion is under way")
        }
    }

    suspend fun current(index: Int): ReverseSwapRecord =
        checkNotNull(store.active()?.takeIf { it.index == index && it.deployment == deployment }) {
            "conversion $index isn't the active one here"
        }

    suspend fun kept(index: Int): ReverseSwapRecord =
        checkNotNull(store.find(index)?.takeIf { it.deployment == deployment }) { "no conversion $index here" }
}

/** What the user's two authorizations need of the conversion, checked under the driver's lock. */
internal class ReverseSwapApprovals(
    private val records: DeploymentRecords,
    private val verifier: ReverseSwapVerifier,
    private val reverseKeys: ReverseSwapKeys,
    private val store: ReverseSwapStore,
) {
    // A conversion under way, not called off, with no escrow yet and time left to fund it.
    suspend fun fundable(index: Int): ReverseSwapRecord {
        val record = records.current(index)
        ReverseSwapDriver.requireGoingAhead(record)
        if (record.funding != null) return record
        checkNotNull(record.account) { "the conversion isn't accepted yet" }
        val observed = verifier.state(record)
        if (observed.swap != null) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.UNDER_WAY_ON_CHAIN, "its escrow is open already")
        }
        ReverseSwapDriver.requireTimeToFund(record.quote, observed.now)
        return record
    }

    suspend fun authorizeReady(
        index: Int,
        estimate: ReverseReceiveEstimate,
    ): SwapAuthorization {
        val record = records.current(index)
        ReverseSwapDriver.requireGoingAhead(record)
        record.ready?.let { return it }
        val observed = verifier.state(record)
        val escrow =
            observed.swap
                ?: throw AtomicSwapBlockedException(AtomicSwapBlock.DEPOSIT_UNCONFIRMED, "the escrow isn't confirmed")
        val isOpen = escrow.stage == SwapStage.OPEN && escrow.refundLockUntil == 0L
        if (!isOpen || observed.now + SIGNATURE_TTL_SECONDS >= record.quote.readyDeadline) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.DEADLINE_PASSED, "too late to authorize settlement")
        }
        val deadline = observed.now + SIGNATURE_TTL_SECONDS
        val authorized = SwapAuthorization(record.swapId, deadline, reverseKeys.signReady(record, deadline).hex())
        store.save(record.copy(ready = authorized, phase = ReversePhase.SETTLING, receiveEstimate = estimate))
        return authorized
    }
}

/** One step of a conversion under way, by what the chain shows of its escrow. */
internal class ReverseSwapStages(
    private val verifier: ReverseSwapVerifier,
    private val opening: ReverseSwapOpening,
    private val receiving: ReverseSwapReceiving,
    private val refunds: ReverseSwapRefunds,
    private val store: ReverseSwapStore,
    private val relayer: SwapRelayer,
) {
    suspend fun advance(
        record: ReverseSwapRecord,
        deposit: ReverseDeposit?,
        outbox: ReverseOutbox,
    ) {
        val observed = verifier.state(record)
        val escrow = observed.swap
        val ready = record.ready
        when {
            escrow == null -> {
                opening.awaitFunding(record, observed, outbox)
            }

            escrow.stage == SwapStage.CLAIMED -> {
                val settled = record.copy(phase = ReversePhase.RECEIVING)
                store.keep(record, settled)
                receiving.receive(settled, escrow.secret, outbox)
            }

            refunds.isDue(record, observed) -> {
                refunds.advance(record, observed, outbox)
            }

            escrow.stage == SwapStage.READY -> {
                store.keep(record, record.copy(phase = ReversePhase.SETTLING))
            }

            // A `ready` the user signed is sent again while it holds, and never signed again after.
            ready != null -> {
                val lapsed = observed.now > ready.deadline
                if (!lapsed && escrow.refundLockUntil == 0L) outbox.send { relayer.ready(ready, record.terms) }
                store.keep(record, record.copy(phase = if (lapsed) ReversePhase.REFUND_WAIT else ReversePhase.SETTLING))
            }

            else -> {
                receiving.awaitDeposit(record, observed, deposit ?: receiving.lookAtDeposit(record))
            }
        }
    }
}

/** What a step sends once what it kept is saved and the lock is let go. */
internal class ReverseOutbox {
    private val sends = mutableListOf<suspend () -> Unit>()

    fun send(send: suspend () -> Unit) {
        sends += send
    }

    suspend fun flush() = sends.forEach { it() }
}

/** Opening a reverse swap: its quote accepted, the joint account imported, and the funding followed to its escrow. */
internal class ReverseSwapOpening(
    private val maker: SwapMaker,
    private val chain: ReverseSwapChain,
    private val submitFunding: suspend (ReverseSwapRecord, ReverseFundingTransaction) -> Unit,
    private val store: ReverseSwapStore,
    private val ending: ReverseSwapEnding,
    private val lock: Mutex,
) {
    /**
     * The maker's acceptance of [record]'s quote, kept so that it's never asked for again: a token pays for each. A
     * refusal another try wouldn't change ends the conversion, before anything is paid.
     */
    suspend fun accept(record: ReverseSwapRecord) {
        try {
            requireAccepted(record)
        } catch (e: AtomicSwapBlockedException) {
            if (e.reason in ENDS_ACCEPTANCE) {
                lock.withLock { kept(record)?.let { ending.finish(it, ReversePhase.CANCELLED) } }
            }
            throw e
        }
        lock.withLock { kept(record)?.let { store.keep(it, it.copy(makerAccepted = true)) } }
    }

    // The maker takes the same keys again, on the quote's deadlines.
    private suspend fun requireAccepted(record: ReverseSwapRecord) {
        val accepted =
            try {
                maker.acceptReverse(record.quote.terms.quoteId, record.swapId, record.acceptance)
            } catch (e: AtomicSwapHttpException.Refused) {
                throw e.code?.let(::lastWord) ?: e
            }
        val quote = record.quote
        val opened = accepted.swapId == record.swapId
        if (!opened || accepted.t0 != quote.readyDeadline || accepted.t1 != quote.refundAfter) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, "the maker opened another swap")
        }
    }

    private suspend fun kept(record: ReverseSwapRecord) = store.active()?.takeIf { it.index == record.index }

    /** Keeps the accepted joint account with [record], which then waits for its funding if nothing else changed. */
    suspend fun keepAccepted(
        record: ReverseSwapRecord,
        account: JointAccountId,
    ) {
        if (record.account != null) return
        val phase = if (record.phase == ReversePhase.ACCEPTING) ReversePhase.AWAITING_FUNDING else record.phase
        store.save(record.copy(account = account, phase = phase))
    }

    /** No escrow on the chain yet: one being funded is followed, and one past its deadline is called off. */
    suspend fun awaitFunding(
        record: ReverseSwapRecord,
        observed: ReverseChainState,
        outbox: ReverseOutbox,
    ) {
        val transaction = record.funding
        when {
            transaction != null -> {
                awaitEscrow(record, transaction, observed, outbox)
            }

            record.cancelRequested || observed.now >= record.quote.fundingDeadline -> {
                ending.finish(record, ReversePhase.CANCELLED)
            }
        }
    }

    // openReverse reverts past the funding deadline: once that is well behind the chain, no escrow is coming.
    private suspend fun awaitEscrow(
        record: ReverseSwapRecord,
        transaction: ReverseFundingTransaction,
        observed: ReverseChainState,
        outbox: ReverseOutbox,
    ) {
        val status = transaction.txId?.let { chain.fundingStatus(it) } ?: TransactionStatus.UNKNOWN
        when {
            status == TransactionStatus.REVERTED ||
                observed.now > record.quote.fundingDeadline + FUNDING_SETTLED_AFTER_SECONDS -> {
                ending.finish(record, ReversePhase.CANCELLED)
            }

            status != TransactionStatus.UNKNOWN -> {
                store.keep(record, record.copy(phase = ReversePhase.CONFIRMING_ESCROW))
            }

            // A funding the node never saw is not sent again once the user called the conversion off.
            !record.cancelRequested && observed.now < record.quote.fundingDeadline -> {
                outbox.send { submitFunding(record, transaction) }
            }
        }
    }
}

/** The joint account as a look at it found it: what sweeping it home would bring, once the whole deposit counts. */
internal class ReverseDeposit(
    val estimate: ReverseReceiveEstimate?,
)

/** A reverse swap's Zcash side: the maker's deposit into the joint account, and the sweep home once claimed. */
internal class ReverseSwapReceiving(
    private val deployment: SwapDeployment,
    private val zcash: ReverseSwapZcash,
    private val store: ReverseSwapStore,
    private val ending: ReverseSwapEnding,
) {
    /** What sweeping the joint account home would bring; it must pay its fee and leave the deposit whole. */
    suspend fun estimate(record: ReverseSwapRecord): ReverseReceiveEstimate =
        lookAtDeposit(record).estimate
            ?: throw AtomicSwapBlockedException(AtomicSwapBlock.DEPOSIT_UNCONFIRMED, "the ZEC isn't all confirmed yet")

    /**
     * The joint account synced to the tip, with the sweep's estimate once the whole deposit has the deployment's
     * confirmations: the wallet counts ZEC from others spendable only much later, and only the sweep waits for that.
     */
    suspend fun lookAtDeposit(record: ReverseSwapRecord): ReverseDeposit {
        val estimate = zcash.estimateReceive(record, deployment.zcashConfirmations)
        if (estimate.availableZat < record.quote.terms.depositZat) return ReverseDeposit(null)
        if (!estimate.isUsable) throw AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, "an empty sweep")
        return ReverseDeposit(estimate)
    }

    suspend fun awaitDeposit(
        record: ReverseSwapRecord,
        observed: ReverseChainState,
        deposit: ReverseDeposit,
    ) {
        val escrow = observed.swap
        val phase =
            when {
                escrow?.stage != SwapStage.OPEN -> {
                    ReversePhase.SETTLING
                }

                escrow.refundLockUntil != 0L || observed.now + SIGNATURE_TTL_SECONDS >= record.quote.readyDeadline -> {
                    ReversePhase.REFUND_WAIT
                }

                deposit.estimate != null -> {
                    ReversePhase.AWAITING_READY
                }

                else -> {
                    ReversePhase.RECEIVING_ZEC
                }
            }
        val estimate = deposit.estimate.takeIf { phase == ReversePhase.AWAITING_READY } ?: record.receiveEstimate
        store.keep(record, record.copy(phase = phase, receiveEstimate = estimate))
    }

    /** The sweep home, kept before it is first sent and sent again until it has its confirmations. */
    suspend fun receive(
        initial: ReverseSwapRecord,
        secret: ByteArray,
        outbox: ReverseOutbox,
    ) {
        var record = initial
        val receive =
            keepSending(
                kept = record.receive,
                status = { zcash.receiveStatus(record, it) },
                build = { zcash.prepareReceive(record, secret) },
                keep = { kept ->
                    record = record.copy(receive = kept, receiveConfirmations = 0, phase = ReversePhase.RECEIVING)
                    store.save(record)
                },
                send = { outbox.send { zcash.submit(it.transaction) } },
            )
        val confirmations = (receive?.second as? ZcashTransactionStatus.Mined)?.confirmations ?: 0
        val received = record.copy(receiveConfirmations = confirmations, phase = ReversePhase.RECEIVING)
        if (confirmations >= deployment.zcashConfirmations) {
            ending.finish(received, ReversePhase.COMPLETE)
        } else {
            store.keep(record, received)
        }
    }
}

/** Ends a reverse swap, then stops watching its joint account. */
internal class ReverseSwapEnding(
    private val store: ReverseSwapStore,
    private val zcash: ReverseSwapZcash,
) {
    suspend fun finish(
        record: ReverseSwapRecord,
        phase: ReversePhase
    ) {
        store.save(record.copy(phase = phase))
        zcash.forget(record)
    }
}

private val ENDS_ACCEPTANCE =
    setOf(
        AtomicSwapBlock.MISMATCH,
        AtomicSwapBlock.MAKER_BUSY,
        AtomicSwapBlock.QUOTE_EXPIRED,
        AtomicSwapBlock.TOKENS_EXHAUSTED,
        AtomicSwapBlock.TOKENS_REFUSED,
        AtomicSwapBlock.TOKENS_NEED_TOR,
    )

// The maker's refusals that no other try would change; an internal error may have accepted, so it's tried again.
private fun lastWord(code: SwapErrorCode): AtomicSwapBlockedException? =
    when (code) {
        SwapErrorCode.UNAVAILABLE, SwapErrorCode.WATCHTOWER_UNAVAILABLE -> {
            AtomicSwapBlockedException(AtomicSwapBlock.MAKER_BUSY, "the maker takes no more conversions for now")
        }

        SwapErrorCode.UNKNOWN_QUOTE -> {
            AtomicSwapBlockedException(AtomicSwapBlock.QUOTE_EXPIRED, "the maker no longer has the quote")
        }

        SwapErrorCode.TOKEN_REQUIRED -> {
            AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_REFUSED, "the maker turned down the token")
        }

        SwapErrorCode.REJECTED,
        SwapErrorCode.INVALID_REQUEST,
        SwapErrorCode.UNKNOWN_SWAP,
        SwapErrorCode.NOT_FOUND,
        SwapErrorCode.METHOD_NOT_ALLOWED -> {
            AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, "the maker refused the quote's acceptance")
        }

        SwapErrorCode.INTERNAL, SwapErrorCode.ALREADY_SPENT -> {
            null
        }
    }

/** How long past its funding deadline a reverse swap waits for its escrow before it's called off. */
internal const val FUNDING_SETTLED_AFTER_SECONDS = 10 * 60L

class ReverseRefundUnavailableException : IllegalStateException("the conversion has already settled")

private fun ReverseSwapRecord.requireRefundPossible() {
    if (isSettled) throw ReverseRefundUnavailableException()
}
