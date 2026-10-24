// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.Bps
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The user's side of a forward swap, after zecSwap's `user.rs`: each step reads the chain first, so can rerun. */
class AtomicSwapDriver(
    private val deployment: SwapDeployment,
    depositTerms: ZcashDepositTerms,
    private val maker: SwapMaker,
    relayer: SwapRelayer,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
    private val zcash: AtomicSwapZcash,
    private val store: AtomicSwapStore,
    private val indices: SwapIndices,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val ending = AtomicSwapEnding(store, nowSeconds)
    private val claim = AtomicSwapClaim(deployment, relayer, chain, keys, store, nowSeconds)
    private val deposits = AtomicSwapDeposits(deployment, depositTerms, chain, keys, zcash, store, ending, nowSeconds)

    // Checked once: every quote is checked against the deployment as well.
    private var isMakerChecked = false

    /** Quotes [requested] for a fresh index, which counts as spent from here on. */
    suspend fun quote(requested: Usdc6): AtomicSwapOffer {
        deployment.requireWindows()
        requireNoSwapUnderWay()
        if (!isMakerChecked) {
            maker.info().requireServing(deployment, reverse = false)
            isMakerChecked = true
        }
        val index = indices.take()
        val quote = maker.quote(requested, keys.authAddress(index), keys.payoutNote(index).commitment)
        if (!deployment.serves(quote)) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the quote is for another deployment")
        }
        val terms = claim.relayerTerms()
        val receives = payoutAfterFees(quote.amount, terms.fee)
        return AtomicSwapOffer(index, requested, quote, terms.fee, receives, feeExpiresAt = terms.feeExpiresAt)
    }

    /** Accepts [offer], saving the record before the maker sees the share, so an accept cut short is still known. */
    suspend fun accept(offer: AtomicSwapOffer): AtomicSwapRecord {
        deployment.requireWindows()
        requireNoSwapUnderWay()
        val quote = offer.quote
        requirePaysOut(quote.amount, offer.relayerFee)
        quote.requireTimeToAccept(nowSeconds())
        val acceptance =
            keys.accept(
                offer.index,
                quote.chainId,
                deployment.contract,
                quote.quoteId.hexToBytes(),
                quote.makerShare,
                quote.makerProof.hexToBytes(),
            )
        val record =
            AtomicSwapRecord(
                index = offer.index,
                quote = quote,
                swapId = SwapId.of(deployment.maker, acceptance.userShare),
                zcashHeight = zcash.chainHeight(),
                acceptedAt = nowSeconds(),
                receives = offer.receives,
                maxTotalZat = offer.maxTotalZat,
                relayerFee = offer.relayerFee,
                feeExpiresAt = offer.feeExpiresAt,
            )
        store.save(record)
        val accepted =
            try {
                maker.accept(quote.quoteId, record.swapId, acceptance.wire())
            } catch (e: AtomicSwapHttpException.Refused) {
                return ending.conclude(record, AtomicSwapOutcome.NothingSent(refusal(e) ?: throw e))
            } catch (e: AtomicSwapBlockedException) {
                // Turned away at the maker's token gate, before anything opened.
                ending.conclude(record, AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_UNAVAILABLE))
                throw e
            }
        // The maker picks the deadlines, the last of the terms checked on chain before anything is paid. Its ticket,
        // kept before any relayer request, goes with each of them.
        val opened =
            record
                .copy(t0 = accepted.t0, t1 = accepted.t1, ticket = accepted.ticketFor(record.swapId))
                .also { store.save(it) }
        // A late window other than the contract's, which the deployment pins, is another deployment's word.
        val late = deployment.windows?.late
        return if (accepted.swapId == record.swapId && (accepted.lateWindow ?: late) == late) {
            opened
        } else {
            ending.conclude(opened, AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH))
        }
    }

    /** One look at the chain: deposits, claims or refunds when due. [onActivity] hears of a slow step first. */
    suspend fun advance(
        record: AtomicSwapRecord,
        onActivity: (AtomicSwapActivity) -> Unit = {},
    ): AtomicSwapStep {
        val end = record.end
        val terms = if (end == null) keys.terms(record, deployment) else null
        val step =
            when {
                end != null -> AtomicSwapStep.Finished(end.outcome)
                terms == null -> notOnChain(record, isAnswered = false)
                else -> step(record, terms, onActivity)
            }
        // The maker hands the accept's token back once it sees the deposit.
        if (end == null && record.deposit != SwapDeposit.NotStarted) maker.collectToken(record.swapId)
        return step
    }

    /** Drops a swap that never reached the chain, such as one whose accept failed. */
    suspend fun abandon(record: AtomicSwapRecord): AtomicSwapStep {
        if (record.deposit != SwapDeposit.NotStarted || chain.swap(record.swapId) != null) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.UNDER_WAY_ON_CHAIN, "the swap is under way: advance it")
        }
        return ending.finish(record, AtomicSwapOutcome.NothingSent(NothingSentCause.NEVER_OPENED))
    }

    private suspend fun requireNoSwapUnderWay() {
        if (store.active()?.finished == false) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a swap is already under way")
        }
    }

    private suspend fun step(
        record: AtomicSwapRecord,
        terms: SwapTerms,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep {
        val swap =
            try {
                chain.confirmedSwap(record.swapId, terms)
            } catch (e: AtomicSwapBlockedException) {
                // A swap under this id with other terms, such as one an earlier use of its index left, gets nothing.
                if (e.reason != AtomicSwapBlock.MISMATCH || record.deposit != SwapDeposit.NotStarted) throw e
                return ending.finish(record, AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH))
            }
        return if (swap == null) notOnChain(record, isAnswered = true) else onChain(record, swap, onActivity)
    }

    // Not at its confirmation depth yet; or, without the maker's answer to the accept and the deadlines it carries,
    // never to be checked nor paid into.
    private suspend fun notOnChain(
        record: AtomicSwapRecord,
        isAnswered: Boolean,
    ): AtomicSwapStep {
        val gaveUp = nowSeconds() > record.quote.expiresAt + OPEN_GRACE_SECONDS
        val cause =
            when {
                !gaveUp || record.deposit != SwapDeposit.NotStarted -> null
                chain.swap(record.swapId) == null -> NothingSentCause.NEVER_OPENED
                isAnswered -> null
                else -> NothingSentCause.MAKER_UNAVAILABLE
            }
        return cause?.let { ending.finish(record, AtomicSwapOutcome.NothingSent(it)) }
            ?: AtomicSwapStep.Waiting(AtomicSwapWait.OPENING)
    }

    private suspend fun onChain(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep =
        when {
            swap.stage == SwapStage.REFUNDED -> {
                deposits.refunded(record, swap, onActivity)
            }

            swap.stage == SwapStage.CLAIMED && swap.paidOut -> {
                finishPaid(record)
            }

            // A swap on a retired lock contract follows rules this build doesn't: nothing is paid into it, claimed, or
            // asked of a relayer. One never paid into ends with nothing sent; one paid into waits for its maker's
            // refund, whose sweep brings the deposit home.
            deployment.retired && record.deposit == SwapDeposit.NotStarted -> {
                ending.finish(record, AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_UNAVAILABLE))
            }

            deployment.retired -> {
                AtomicSwapStep.Waiting(AtomicSwapWait.RETIRED)
            }

            swap.stage == SwapStage.CLAIMED -> {
                onActivity(AtomicSwapActivity.PAYING_OUT)
                claim.payOut(record, swap)
                finishPaid(record)
            }

            swap.stage == SwapStage.READY -> {
                claim.claim(record, swap, onActivity) ?: finishPaid(record)
            }

            else -> {
                open(record, swap, onActivity)
            }
        }

    // The deposit's window and the claim's go by the same read: until t0 the claim waits for the maker's ready, and
    // from t0 a maker gone quiet holds the deposit no longer.
    private suspend fun open(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep {
        val read = chain.swapNow(record.swapId, swap.terms)
        val nothingSent = deposits.ensurePaid(record, swap, read, onActivity)
        return if (nothingSent != null) {
            ending.finish(record, AtomicSwapOutcome.NothingSent(nothingSent))
        } else {
            claim.claim(record, swap, onActivity, read) ?: finishPaid(record)
        }
    }

    // A relayer's response is not the payout receipt. Keep the confirmed contract event and the amount it paid.
    private suspend fun finishPaid(record: AtomicSwapRecord): AtomicSwapStep.Finished {
        val paid =
            chain.confirmedPayout(record.swapId, record.acceptedAt)
                ?: throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "the payout event isn't confirmed")
        RelayerTerms(paid.relayer, deployment.chainId, deployment.contract, paid.fee)
            .checkedFee(deployment, record.quote.amount)
        return ending.finish(
            record,
            AtomicSwapOutcome.Paid,
            paid.transaction,
            payoutAfterFees(record.quote.amount, paid.fee),
        )
    }

    private companion object {
        // A maker's open can queue behind others; past this it isn't coming, or too late to deposit into.
        const val OPEN_GRACE_SECONDS = 5 * 60L

        // These come before the maker opens anything; a 500 or no answer may follow an open.
        fun refusal(refused: AtomicSwapHttpException.Refused): NothingSentCause? =
            when (refused.code) {
                SwapErrorCode.REJECTED,
                SwapErrorCode.INVALID_REQUEST,
                SwapErrorCode.NOT_FOUND,
                SwapErrorCode.METHOD_NOT_ALLOWED,
                SwapErrorCode.TOKEN_REQUIRED -> NothingSentCause.MAKER_REFUSED

                SwapErrorCode.UNKNOWN_QUOTE -> NothingSentCause.QUOTE_EXPIRED

                SwapErrorCode.UNAVAILABLE, SwapErrorCode.WATCHTOWER_UNAVAILABLE -> NothingSentCause.MAKER_UNAVAILABLE

                SwapErrorCode.UNKNOWN_SWAP, SwapErrorCode.INTERNAL -> null

                // A relayer's answers to a Railgun send or a deposit, which no maker gives: read as a code this build
                // doesn't know.
                SwapErrorCode.ALREADY_SPENT, SwapErrorCode.CONFLICT, null -> refusal(refused.status)
            }

        fun refusal(status: Int): NothingSentCause? =
            when (status) {
                HttpStatusCode.BadRequest.value -> NothingSentCause.MAKER_REFUSED
                HttpStatusCode.NotFound.value -> NothingSentCause.QUOTE_EXPIRED
                HttpStatusCode.ServiceUnavailable.value -> NothingSentCause.MAKER_UNAVAILABLE
                else -> null
            }

        fun SwapDeployment.serves(quote: SwapQuote): Boolean =
            quote.chainId == chainId && quote.contract == contract && quote.token == token && quote.maker == maker
    }
}

/** What reaches Railgun of [amount]: the relayer's fee comes off first, then Railgun's shield fee. */
internal fun payoutAfterFees(
    amount: Usdc6,
    relayerFee: Usdc6
): Usdc6 {
    if (relayerFee >= amount) return Usdc6.ZERO
    val shielded = amount.micros - relayerFee.micros
    val fee = shielded * bigIntegerValueOf(RAILGUN_FEE.value.toLong()) / bigIntegerValueOf(Bps.MAX.toLong())
    return Usdc6(shielded - fee)
}

private fun SwapQuote.requireTimeToAccept(now: Long) {
    if (now >= expiresAt - ACCEPT_MARGIN_SECONDS) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.QUOTE_EXPIRED, "the quote runs out too soon to accept")
    }
}

private const val ACCEPT_MARGIN_SECONDS = 15L

/** Railgun's fee for shielding; it charges the same to unshield. */
val RAILGUN_FEE = Bps(value = 25)

/** The terms [record]'s swap opened with: its checked quote, our keys, and the deadlines the maker answered with. */
internal suspend fun AtomicSwapKeys.terms(
    record: AtomicSwapRecord,
    deployment: SwapDeployment,
): SwapTerms? {
    val t0 = record.t0
    val t1 = record.t1
    if (t0 == null || t1 == null) return null
    return SwapTerms(
        maker = deployment.maker,
        token = deployment.token,
        amount = record.quote.amount,
        makerKey = record.quote.makerShare,
        userKey = userShare(record.index),
        user = authAddress(record.index),
        t0 = t0,
        t1 = t1,
        payoutNote = payoutNote(record.index).commitment,
    )
}

/** Claiming into Railgun through a relayer: the hand-over, the claim's window, the reveal, and the payout. */
internal class AtomicSwapClaim(
    private val deployment: SwapDeployment,
    private val relayer: SwapRelayer,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
    private val store: AtomicSwapStore,
    private val nowSeconds: () -> Long,
) {
    private val handOver =
        AtomicSwapHandOver(deployment, relayer, keys, store) { record, swap, fee -> payout(record, swap, fee) }

    /**
     * Hands the claim to the relayer once the deposit is out. Reveals `z` once the claim's window lets it go with the
     * margin to spare, judged on one consistent read ([read], or a fresh one) and again on a fresh one right before the
     * share leaves, then pays out. Null once the claim has landed; otherwise what it waits for, with nothing sent.
     */
    suspend fun claim(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
        read: SwapRead? = null,
    ): AtomicSwapStep.Waiting? {
        val current = handOver.handOver(record, swap)
        waiting(current, swap, read ?: chain.swapNow(record.swapId, swap.terms))?.let { return it }
        onActivity(AtomicSwapActivity.CLAIMING)
        // A payout Railgun won't take would sit in the contract: better the swap unwinds, the ZEC left with the user.
        requireRailgunOpen(swap)
        val payout = payout(record, swap)
        val secret = keys.claimSecret(record.index).hex()
        sendPayout(record, swap, payout) {
            // Deriving keys, signing and the relayer's terms all suspend: the window may have moved on meanwhile.
            if (waiting(current, swap, chain.swapNow(record.swapId, swap.terms)) != null) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.CLAIM_WINDOW_SHUT, "the claim's window shut meanwhile")
            }
            keepPosted(record)
            relayer.claim(SwapReveal(record.swapId, secret, it), swap.terms, record.ticket)
        }
        val claimed = chain.caughtUp(record.swapId, swap.terms) { it.stage == SwapStage.CLAIMED }
        if (!claimed.paidOut) payOut(record, claimed)
        return null
    }

    /** Retries a revealed claim's payout. */
    suspend fun payOut(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        val payout = payout(record, swap)
        requireRailgunOpen(swap)
        sendPayout(record, swap, payout) { relayer.payout(it, swap.terms, record.ticket) }
        chain.caughtUp(record.swapId, swap.terms) { it.stage == SwapStage.CLAIMED && it.paidOut }
    }

    suspend fun relayerTerms(): RelayerTerms = relayer.terms().also { it.checkedFee(deployment) }

    // Nothing is sent on a refusal: one turning down the fee is signed again at the relayer's fee now, and sent again.
    private suspend fun sendPayout(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        payout: SwapPayout,
        request: suspend (SwapPayout) -> Unit,
    ) {
        try {
            request(payout)
        } catch (e: AtomicSwapHttpException.Refused) {
            if (e.code != SwapErrorCode.REJECTED) throw e
            val fee = relayer.terms().checkedFee(deployment)
            if (fee <= payout.fee) throw e
            request(payout(record, swap, repriced = fee))
        }
    }

    private suspend fun requireRailgunOpen(swap: OnChainSwap) {
        if (!chain.railgunAccepts(swap.token)) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.RAILGUN_CLOSED, "Railgun is not taking the payout now")
        }
    }

    // The user can't claim without the relayer, so a claim pays its fee: the offer's while it holds, or its fee now.
    private suspend fun payout(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        repriced: Usdc6? = null,
    ): SwapPayout {
        val current = checkNotNull(store.active()?.takeIf { it.index == record.index && !it.finished })
        val note = keys.payoutNote(current.index)
        val saved = current.payout
        if (saved != null) {
            check(saved.swapId == current.swapId && saved.note == note.wire()) {
                "the saved payout is for another swap"
            }
        }
        val asked = relayer.terms().checkedFee(deployment)
        val expiresAt = current.feeExpiresAt
        val fee =
            when {
                repriced != null -> repriced
                expiresAt != null && nowSeconds() < expiresAt -> current.relayerFee
                else -> asked
            }
        requirePaysOut(swap.amount, fee)
        if (saved?.fee == fee) return saved
        val signature =
            keys.signPayout(
                record.index,
                record.quote.chainId,
                deployment.contract,
                record.swapId,
                deployment.relayer,
                fee,
            )
        return SwapPayout(record.swapId, note.wire(), fee, signature.hex()).also {
            store.save(current.copy(payout = it))
        }
    }

    /**
     * What the claim waits for on [read], or null when its window lets it go now: on a ready swap, or from `t0`, until
     * `t1` less the margin, by the later of the block's time and this device's clock; before `t0` only on a ready
     * [READY_DEPTH] blocks deep; and from `t1` only once the maker's late window is over. A claim the relayer may hold
     * is its to send as the window opens: the app sends it only once the window was open a minute back too. A head
     * minutes behind this device's clock is refused: its windows have moved on.
     */
    private suspend fun waiting(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        read: SwapRead,
    ): AtomicSwapStep.Waiting? {
        val windows = deployment.requireWindows()
        val head = read.head
        val wall = nowSeconds()
        freshHead(head.time, wall)
        val stage =
            read.swap?.stage
                ?: throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "the newest block shows no swap")

        suspend fun waitsAt(
            at: ChainHead,
            clock: Long,
        ): AtomicSwapWait? =
            when (val judged = windows.forwardClaim(stage, swap.t0, swap.t1, at, clock, CLAIM_MARGIN_SECONDS)) {
                Go.Now -> null
                is Go.OnceReadyAt -> AtomicSwapWait.READY_DEEPENING.takeUnless { isReadyAt(record, swap, judged.block) }
                is Refusal.MakersUntil, Refusal.TooSoon -> AtomicSwapWait.UNWINDING
                Refusal.Shut, Refusal.ReadyMayLand -> AtomicSwapWait.CONFIRMING
            }

        // A minute back, the head is RELAYER_WAIT_BLOCKS lower, and a ready before t0 counts as deep below that.
        val wait =
            waitsAt(head, wall)
                ?: if (record.isHandedOver) waitsAt(head.before(), wall - RELAYER_WAIT_SECONDS) else null
        return wait?.let { AtomicSwapStep.Waiting(it.heldBy(record), swap.t0, swap.t1) }
    }

    private suspend fun isReadyAt(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        block: Long,
    ): Boolean = chain.swapAt(record.swapId, swap.terms, block)?.stage == SwapStage.READY

    // The share goes to the relayer from here on: kept before the first post, so a restart knows it went.
    private suspend fun keepPosted(record: AtomicSwapRecord) {
        val current = checkNotNull(store.active()?.takeIf { it.index == record.index && !it.finished })
        if (current.claimPostedAt == null) store.save(current.copy(claimPostedAt = nowSeconds()))
    }

    private companion object {
        /**
         * How long a claim the relayer may hold is left to it once its window opens; then the app claims itself.
         * Before `t0` the window opens on a ready [READY_DEPTH] blocks deep, so there the wait counts Ethereum's
         * 12-second slots too.
         */
        const val RELAYER_WAIT_SECONDS = 60L
        const val RELAYER_WAIT_BLOCKS = 5L

        // Posted to the relayer and not turned down: it may hold the claim.
        val AtomicSwapRecord.isHandedOver: Boolean
            get() = claimHold == ClaimHold.POSTED || claimHold == ClaimHold.HELD

        // The head and its time a minute back.
        fun ChainHead.before() =
            ChainHead((number - RELAYER_WAIT_BLOCKS).coerceAtLeast(0), time - RELAYER_WAIT_SECONDS)

        // Waiting for the window to open, the claim the relayer holds is its to send: the user may leave.
        fun AtomicSwapWait.heldBy(record: AtomicSwapRecord): AtomicSwapWait =
            if (record.claimHold == ClaimHold.HELD && this in WINDOW_WAITS) AtomicSwapWait.CLAIM_HELD else this

        val WINDOW_WAITS = setOf(AtomicSwapWait.CONFIRMING, AtomicSwapWait.READY_DEEPENING)
    }
}

/** Ends a swap. The end goes on the stored record, which may be newer than the caller's copy within one step. */
internal class AtomicSwapEnding(
    private val store: AtomicSwapStore,
    private val nowSeconds: () -> Long,
) {
    suspend fun finish(
        record: AtomicSwapRecord,
        outcome: AtomicSwapOutcome,
        payoutTx: TxHash? = null,
        receives: Usdc6? = null,
    ): AtomicSwapStep.Finished {
        conclude(record, outcome, payoutTx, receives)
        return AtomicSwapStep.Finished(outcome)
    }

    suspend fun conclude(
        record: AtomicSwapRecord,
        outcome: AtomicSwapOutcome,
        payoutTx: TxHash? = null,
        receives: Usdc6? = null,
    ): AtomicSwapRecord {
        val current = store.active()?.takeIf { it.index == record.index } ?: record
        return current
            .copy(
                end = SwapEnd(outcome, nowSeconds()),
                payoutTx = payoutTx ?: current.payoutTx,
                receives = receives ?: current.receives,
            ).also { store.save(it) }
    }
}

/** The swap once our RPC node shows what [seen] expects: it can lag the node a transaction went through. */
internal suspend fun AtomicSwapChainReader.caughtUp(
    swapId: SwapId,
    terms: SwapTerms,
    seen: (OnChainSwap) -> Boolean
): OnChainSwap {
    repeat(CATCH_UP_POLLS) {
        confirmedSwap(swapId, terms)?.takeIf(seen)?.let { return it }
        delay(CATCH_UP_INTERVAL)
    }
    throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "our RPC node never showed the swap as expected")
}

/** [head], the latest block time, unless it trails this device's clock by minutes: a window shut would look open. */
internal fun freshHead(
    head: Long,
    deviceNow: Long
): Long {
    if (deviceNow - head > MAX_HEAD_LAG_SECONDS) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "the chain's head is ${deviceNow - head}s old")
    }
    return head
}

private const val CATCH_UP_POLLS = 30
private val CATCH_UP_INTERVAL = 2.seconds
private const val MAX_HEAD_LAG_SECONDS = 3 * 60L
