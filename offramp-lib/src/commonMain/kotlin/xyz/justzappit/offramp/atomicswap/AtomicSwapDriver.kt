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
    val payoutFees = AtomicSwapFeeApproval(claim, store)
    private val deposits = AtomicSwapDeposits(deployment, depositTerms, chain, keys, zcash, store, ending)

    // Checked once: every quote is checked against the deployment as well.
    private var isMakerChecked = false

    /** Quotes [requested] for a fresh index, which counts as spent from here on. */
    suspend fun quote(requested: Usdc6): AtomicSwapOffer {
        requireNoSwapUnderWay()
        if (!isMakerChecked) {
            maker.info().requireServing(deployment, reverse = false)
            isMakerChecked = true
        }
        val index = indices.take()
        val railgunKeys = RailgunKeySource.BIP85
        val quote = maker.quote(requested, keys.authAddress(index), keys.payoutNote(index, railgunKeys).commitment)
        if (!deployment.serves(quote)) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the quote is for another deployment")
        }
        val relayerFee = claim.relayerFee(quote.amount)
        val receives = payoutAfterFees(quote.amount, relayerFee)
        return AtomicSwapOffer(index, requested, quote, relayerFee, receives, railgunKeys)
    }

    /** Accepts [offer], saving the record before the maker sees the share, so an accept cut short is still known. */
    suspend fun accept(offer: AtomicSwapOffer): AtomicSwapRecord {
        requireNoSwapUnderWay()
        val quote = offer.quote
        if (nowSeconds() >= quote.expiresAt - ACCEPT_MARGIN_SECONDS) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.QUOTE_EXPIRED, "the quote runs out too soon to accept")
        }
        val acceptance =
            keys.accept(
                offer.index,
                offer.railgunKeys,
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
                railgunKeys = offer.railgunKeys,
            )
        store.save(record)
        val opened =
            try {
                maker.accept(quote.quoteId, acceptance.wire())
            } catch (e: AtomicSwapHttpException.Refused) {
                return ending.conclude(record, AtomicSwapOutcome.NothingSent(refusal(e) ?: throw e))
            }
        return if (opened == record.swapId) {
            record
        } else {
            ending.conclude(record, AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH))
        }
    }

    /** One look at the chain: deposits, claims or refunds when due. [onActivity] hears of a slow step first. */
    suspend fun advance(
        record: AtomicSwapRecord,
        onActivity: (AtomicSwapActivity) -> Unit = {},
    ): AtomicSwapStep {
        val end = record.end
        val swap = if (end == null) chain.confirmedSwap(record.swapId) else null
        return when {
            end != null -> {
                AtomicSwapStep.Finished(end.outcome)
            }

            swap == null -> {
                notOnChain(record)
            }

            keys.matches(record, swap, deployment) -> {
                onChain(record, swap, onActivity)
            }

            // A swap under this id that isn't this record's, such as one an earlier use of its index left.
            record.deposit == SwapDeposit.NotStarted -> {
                ending.finish(record, AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH))
            }

            // ZEC may have gone into it all the same: nothing is claimed, but its refund still comes home.
            swap.stage == SwapStage.REFUNDED -> {
                deposits.refunded(record, swap, onActivity)
            }

            else -> {
                throw AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, "the swap isn't the one accepted")
            }
        }
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

    private suspend fun notOnChain(record: AtomicSwapRecord): AtomicSwapStep {
        val gaveUp = nowSeconds() > record.quote.expiresAt + OPEN_GRACE_SECONDS
        return if (record.deposit == SwapDeposit.NotStarted && gaveUp && chain.swap(record.swapId) == null) {
            ending.finish(record, AtomicSwapOutcome.NothingSent(NothingSentCause.NEVER_OPENED))
        } else {
            AtomicSwapStep.Waiting(AtomicSwapWait.OPENING)
        }
    }

    private suspend fun onChain(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep =
        when (swap.stage) {
            SwapStage.REFUNDED -> {
                deposits.refunded(record, swap, onActivity)
            }

            SwapStage.CLAIMED -> {
                if (!swap.paidOut) {
                    onActivity(AtomicSwapActivity.PAYING_OUT)
                    claim.payOut(record, swap)
                }
                finishPaid(record)
            }

            SwapStage.READY -> {
                onActivity(AtomicSwapActivity.CLAIMING)
                claim.claim(record, swap)
                finishPaid(record)
            }

            SwapStage.OPEN -> {
                open(record, swap, onActivity)
            }
        }

    private suspend fun open(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep {
        val nothingSent = deposits.ensurePaid(record, swap, onActivity)
        return when {
            nothingSent != null -> {
                ending.finish(record, AtomicSwapOutcome.NothingSent(nothingSent))
            }

            // Past t0 the claim needs no `ready`: a maker gone quiet holds the deposit no longer.
            chain.now() >= swap.t0 -> {
                onActivity(AtomicSwapActivity.CLAIMING)
                claim.claim(record, swap)
                finishPaid(record)
            }

            else -> {
                AtomicSwapStep.Waiting(AtomicSwapWait.CONFIRMING, swap.t0, swap.t1)
            }
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
        const val ACCEPT_MARGIN_SECONDS = 15L

        // A maker's open can queue behind others; past this it isn't coming, or too late to deposit into.
        const val OPEN_GRACE_SECONDS = 5 * 60L

        // These come before the maker opens anything; a 500 or no answer may follow an open.
        fun refusal(refused: AtomicSwapHttpException.Refused): NothingSentCause? =
            when (refused.code) {
                SwapErrorCode.REJECTED,
                SwapErrorCode.INVALID_REQUEST,
                SwapErrorCode.NOT_FOUND,
                SwapErrorCode.METHOD_NOT_ALLOWED -> NothingSentCause.MAKER_REFUSED

                SwapErrorCode.UNKNOWN_QUOTE -> NothingSentCause.QUOTE_EXPIRED

                SwapErrorCode.UNAVAILABLE, SwapErrorCode.WATCHTOWER_UNAVAILABLE -> NothingSentCause.MAKER_UNAVAILABLE

                SwapErrorCode.UNKNOWN_SWAP, SwapErrorCode.INTERNAL -> null

                null -> refusal(refused.status)
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
    val shielded = amount.micros - relayerFee.micros
    val fee = shielded * bigIntegerValueOf(RAILGUN_FEE.value.toLong()) / bigIntegerValueOf(Bps.MAX.toLong())
    return Usdc6(shielded - fee)
}

/** Railgun's fee for shielding; it charges the same to unshield. */
val RAILGUN_FEE = Bps(value = 25)

private suspend fun AtomicSwapKeys.matches(
    record: AtomicSwapRecord,
    swap: OnChainSwap,
    deployment: SwapDeployment,
): Boolean =
    swap.maker == deployment.maker &&
        swap.makerShare == record.quote.makerShare &&
        swap.userShare == userShare(record.index) &&
        swap.user == authAddress(record.index) &&
        swap.payoutNote == payoutNote(record.index, record.railgunKeys).commitment &&
        swap.token == deployment.token &&
        swap.amount == record.quote.amount

/** Claiming into Railgun through a relayer: the lock, the reveal, and the payout. */
internal class AtomicSwapClaim(
    private val deployment: SwapDeployment,
    private val relayer: SwapRelayer,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
    private val store: AtomicSwapStore,
    private val nowSeconds: () -> Long,
) {
    /** Reveals `z` under a held claim lock once Railgun would take the payout at the quoted fee, then pays out. */
    suspend fun claim(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        val payout = payout(record, swap)
        requireRailgunOpen(swap)
        holdClaimLock(record, swap)
        // Key derivation may suspend too. Read the lock and the clock again after every preparatory step.
        val reveal = SwapReveal(record.swapId, keys.claimSecret(record.index).hex(), payout)
        requireFreshClaimLock(record)
        relayer.claim(reveal)
        val claimed = chain.caughtUp(record.swapId) { it.stage == SwapStage.CLAIMED }
        if (!claimed.paidOut) payOut(record, claimed)
    }

    /** Retries a revealed claim's payout without expanding the fee authorization the user reviewed. */
    suspend fun payOut(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        val payout = payout(record, swap)
        requireRailgunOpen(swap)
        relayer.payout(payout)
        chain.caughtUp(record.swapId) { it.stage == SwapStage.CLAIMED && it.paidOut }
    }

    suspend fun relayerFee(amount: Usdc6): Usdc6 = relayer.terms().checkedFee(deployment, amount)

    private suspend fun requireRailgunOpen(swap: OnChainSwap) {
        if (!chain.railgunAccepts(swap.token)) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.RAILGUN_CLOSED, "Railgun is not taking the payout now")
        }
    }

    private suspend fun payout(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
    ): SwapPayout {
        val current = checkNotNull(store.active()?.takeIf { it.index == record.index && !it.finished })
        val note = keys.payoutNote(current.index, current.railgunKeys)
        val saved = current.payout
        if (saved != null) {
            check(saved.swapId == current.swapId && saved.note == note.wire()) {
                "the saved payout is for another swap"
            }
        }
        val limit = current.relayerFee
        val fee =
            when {
                limit != null -> {
                    relayer.terms().checkedFee(deployment, swap.amount, minOf(limit, deployment.maxRelayerFee))
                }

                saved != null -> {
                    RelayerTerms(deployment.relayer, deployment.chainId, deployment.contract, saved.fee)
                        .checkedFee(deployment, swap.amount)
                }

                else -> {
                    throw AtomicSwapBlockedException(
                        AtomicSwapBlock.RELAYER_FEE,
                        "review and approve the payout fee first",
                    )
                }
            }
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

    // A reveal must land before the lock lapses, or the maker's next turn knows both halves; and no lock is asked
    // for that the contract won't give, such as in the maker's turn after ours lapsed.
    private suspend fun holdClaimLock(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        val now = freshHead(chain.now(), nowSeconds())
        if (swap.claimLockUntil > now && swap.claimLockUntil - now > REVEAL_MARGIN_SECONDS) return
        val lockDuration = chain.lockDuration()
        if (!mayTakeLock(swap.claimLockUntil, swap.refundLockUntil, now, lockDuration)) {
            throw AtomicSwapBlockedException(
                AtomicSwapBlock.CLAIM_LOCK_LAPSING,
                "no claim lock to reveal under; claim once the next turn is ours",
            )
        }
        check(SIGNATURE_TTL_SECONDS < lockDuration) { "the lock is too short to sign for" }
        check(now <= Long.MAX_VALUE - SIGNATURE_TTL_SECONDS) { "invalid chain time" }
        val deadline = now + SIGNATURE_TTL_SECONDS
        val signature =
            keys.signLockClaim(record.index, record.quote.chainId, deployment.contract, record.swapId, deadline)
        relayer.lockClaim(SwapAuthorization(record.swapId, deadline, signature.hex()))
        chain.caughtUp(record.swapId) { it.claimLockUntil > now }
    }

    private suspend fun requireFreshClaimLock(record: AtomicSwapRecord) {
        val fresh =
            chain.confirmedSwap(record.swapId)
                ?: throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "the claim lock isn't confirmed")
        val matches = keys.matches(record, fresh, deployment)
        val now = freshHead(chain.now(), nowSeconds())
        val unsafe =
            (fresh.stage != SwapStage.OPEN && fresh.stage != SwapStage.READY) ||
                fresh.claimLockUntil <= now || fresh.claimLockUntil - now <= REVEAL_MARGIN_SECONDS
        when {
            !matches -> AtomicSwapBlock.MISMATCH
            unsafe -> AtomicSwapBlock.CLAIM_LOCK_LAPSING
            else -> null
        }?.let { reason ->
            throw AtomicSwapBlockedException(reason, "no matching swap with a safe claim lock to reveal under")
        }
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
    seen: (OnChainSwap) -> Boolean
): OnChainSwap {
    repeat(CATCH_UP_POLLS) {
        confirmedSwap(swapId)?.takeIf(seen)?.let { return it }
        delay(CATCH_UP_INTERVAL)
    }
    throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "our RPC node never showed the swap as expected")
}

/** [head], the latest block time, unless it trails this device's clock by minutes: a lapsed lock would look held. */
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
