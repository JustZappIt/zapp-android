// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The user's side of a swap selling shielded ZEC into their Railgun balance, step by step as
 * zecSwap's reference client (`crates/zecswap-client/src/user.rs`) takes it. Each step reads the
 * chain before acting, so any of them can run again after an interruption.
 */
class AtomicSwapDriver(
    private val config: AtomicSwapConfig,
    private val maker: MakerClient,
    relayer: RelayerClient,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
    private val zcash: AtomicSwapZcash,
    private val store: AtomicSwapStore,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val claim = AtomicSwapClaim(config, relayer, chain, keys)
    private val deposits = AtomicSwapDeposits(config, chain, keys, zcash, store)

    /** Quotes [units] for a fresh index, which counts as spent from here on. */
    suspend fun quote(units: Int): AtomicSwapOffer {
        requireNoSwapUnderWay()
        val index = store.takeIndex()
        val quote = maker.quote(units, keys.authAddress(index), keys.payoutNote(index).commitment)
        if (quote.chainId != config.chainId ||
            Address.parse(quote.contract) != config.contract ||
            Address.parse(quote.token) != config.token
        ) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the quote is for another deployment")
        }
        val relayerFee = claim.relayerFee(BigInteger(quote.amount))
        return AtomicSwapOffer(index, units, quote, relayerFee, payoutAfterFees(BigInteger(quote.amount), relayerFee))
    }

    /**
     * Accepts [offer]. The record is saved before the maker sees the share, so an accept cut short is
     * still known; one the maker turns down comes back finished, with nothing sent.
     */
    suspend fun accept(offer: AtomicSwapOffer): AtomicSwapRecord {
        requireNoSwapUnderWay()
        val quote = offer.quote
        if (nowSeconds() >= quote.expiresAt - ACCEPT_MARGIN_SECONDS) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.QUOTE_EXPIRED, "the quote runs out too soon to accept")
        }
        val acceptance =
            keys.accept(
                offer.index,
                quote.chainId,
                config.contract,
                quote.quoteId.hexToBytes(),
                quote.makerShare.hexToBytes(),
                quote.makerProof.hexToBytes(),
            )
        val swapId = AtomicSwapChain.swapId(Address.parse(quote.maker), acceptance.userShare)
        val record =
            AtomicSwapRecord(
                index = offer.index,
                quote = quote,
                swapId = swapId.hex(),
                zcashHeight = zcash.chainHeight(),
                acceptedAt = nowSeconds(),
                receives = offer.receives.toString(),
            )
        store.save(record)
        return try {
            maker.accept(quote.quoteId, acceptance)
            record
        } catch (e: AtomicSwapHttpException) {
            val cause = refusal(e.status) ?: throw e
            conclude(record, AtomicSwapOutcome.NothingSent(cause))
        }
    }

    /**
     * One look at the chain: deposits, claims or refunds when due, or says what it waits for.
     * [onActivity] hears of a slow step before it starts.
     */
    suspend fun advance(
        record: AtomicSwapRecord,
        onActivity: (AtomicSwapActivity) -> Unit = {},
    ): AtomicSwapStep {
        val outcome = record.outcome
        val swap = if (outcome == null) chain.swap(record.swapId.hexToBytes()) else null
        return when {
            outcome != null -> AtomicSwapStep.Finished(outcome)
            swap == null -> notOnChain(record)
            else -> onChain(record, swap, onActivity)
        }
    }

    /** Drops a swap that never reached the chain, such as one whose accept failed. */
    suspend fun abandon(record: AtomicSwapRecord): AtomicSwapStep {
        if (chain.swap(record.swapId.hexToBytes()) != null || record.depositAttempted) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.UNDER_WAY_ON_CHAIN, "the swap is under way: advance it")
        }
        return finish(record, AtomicSwapOutcome.NothingSent(NothingSentCause.NEVER_OPENED))
    }

    private suspend fun requireNoSwapUnderWay() {
        if (store.active()?.finished == false) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a swap is already under way")
        }
    }

    private suspend fun notOnChain(record: AtomicSwapRecord): AtomicSwapStep =
        if (!record.depositAttempted && nowSeconds() > record.quote.expiresAt + OPEN_GRACE_SECONDS) {
            finish(record, AtomicSwapOutcome.NothingSent(NothingSentCause.NEVER_OPENED))
        } else {
            AtomicSwapStep.Waiting(AtomicSwapWait.OPENING)
        }

    private suspend fun onChain(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep =
        when (swap.stage) {
            SwapStage.REFUNDED -> {
                finish(record, deposits.refund(record, swap, onActivity))
            }

            SwapStage.CLAIMED -> {
                val payoutTx =
                    if (swap.paidOut) {
                        null
                    } else {
                        onActivity(AtomicSwapActivity.PAYING_OUT)
                        claim.payOut(record, swap)
                    }
                finish(record, AtomicSwapOutcome.Paid, payoutTx)
            }

            SwapStage.READY -> {
                onActivity(AtomicSwapActivity.CLAIMING)
                finish(record, AtomicSwapOutcome.Paid, claim.claim(record, swap))
            }

            SwapStage.OPEN -> {
                open(record, swap, onActivity)
            }
        }

    // A maker that is silent past t0 no longer holds the deposit; claim it then.
    private suspend fun open(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): AtomicSwapStep =
        when {
            record.depositTxId == null -> {
                when (deposits.pay(record, swap, onActivity)) {
                    DepositResult.PAID -> waiting(AtomicSwapWait.CONFIRMING, swap)
                    DepositResult.UNSETTLED -> waiting(AtomicSwapWait.DEPOSIT_UNSETTLED, swap)
                    DepositResult.MISMATCH -> finish(record, nothingSent(NothingSentCause.MISMATCH))
                    DepositResult.TOO_LATE -> finish(record, nothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED))
                }
            }

            chain.now() >= swap.t0 -> {
                onActivity(AtomicSwapActivity.CLAIMING)
                finish(record, AtomicSwapOutcome.Paid, claim.claim(record, swap))
            }

            else -> {
                waiting(AtomicSwapWait.CONFIRMING, swap)
            }
        }

    private suspend fun finish(
        record: AtomicSwapRecord,
        outcome: AtomicSwapOutcome,
        payoutTx: String? = null,
    ): AtomicSwapStep {
        conclude(record, outcome, payoutTx)
        return AtomicSwapStep.Finished(outcome)
    }

    // The stored record may be newer than the caller's copy within one step.
    private suspend fun conclude(
        record: AtomicSwapRecord,
        outcome: AtomicSwapOutcome,
        payoutTx: String? = null,
    ): AtomicSwapRecord {
        val current = store.active()?.takeIf { it.index == record.index } ?: record
        return current
            .copy(outcome = outcome, finishedAt = nowSeconds(), payoutTx = payoutTx ?: current.payoutTx)
            .also { store.save(it) }
    }

    private companion object {
        const val ACCEPT_MARGIN_SECONDS = 15L

        // A maker's open can queue behind others; past this it isn't coming, or too late to deposit into.
        const val OPEN_GRACE_SECONDS = 5 * 60L

        fun nothingSent(cause: NothingSentCause) = AtomicSwapOutcome.NothingSent(cause)

        fun waiting(
            reason: AtomicSwapWait,
            swap: OnChainSwap
        ) = AtomicSwapStep.Waiting(reason, swap.t0, swap.t1)

        // These come before the maker opens anything; a 500 or no answer may follow an open.
        fun refusal(status: Int?): NothingSentCause? =
            when (status) {
                HttpStatusCode.BadRequest.value -> NothingSentCause.MAKER_REFUSED
                HttpStatusCode.NotFound.value -> NothingSentCause.QUOTE_EXPIRED
                HttpStatusCode.ServiceUnavailable.value -> NothingSentCause.MAKER_UNAVAILABLE
                else -> null
            }
    }
}

/** What reaches Railgun of [amount]: the relayer's fee comes off first, then Railgun's shield fee. */
private fun payoutAfterFees(
    amount: BigInteger,
    relayerFee: BigInteger
): BigInteger {
    val shielded = amount - relayerFee
    return shielded - shielded * bigIntegerValueOf(RAILGUN_SHIELD_FEE_BPS) / bigIntegerValueOf(BPS)
}

private const val RAILGUN_SHIELD_FEE_BPS = 25L
private const val BPS = 10_000L

/** Claiming into Railgun through a relayer: the lock, the reveal, and the payout. */
internal class AtomicSwapClaim(
    private val config: AtomicSwapConfig,
    private val relayer: RelayerClient,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
) {
    /**
     * Reveals `z` under a claim lock with time to spare, then has the payout sent, and returns the
     * payout's transaction. Checks first that Railgun would take it: a payout it refuses would sit in
     * the contract, and it is better for the swap to unwind, which leaves the ZEC with the user.
     */
    suspend fun claim(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): String? {
        val payout = payoutRequest(record, swap)
        if (!chain.railgunAccepts(swap.token)) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.RAILGUN_CLOSED, "Railgun is not taking the payout now")
        }
        holdClaimLock(record, swap)
        val sent = relayer.claim(ClaimRequest(record.swapId, keys.claimSecret(record.index).hex(), payout))
        chain.caughtUp(record.swapId) { it.stage == SwapStage.CLAIMED }
        // The relayer reveals first and pays out after; a payout that failed is sent again.
        val payoutTx =
            if (sent.transactions.size < 2) {
                relayer.payout(payout).transactions.lastOrNull()
            } else {
                sent.transactions.last()
            }
        chain.caughtUp(record.swapId) { it.paidOut }
        return payoutTx
    }

    /** A claim revealed before an interruption: only the payout is left. Returns its transaction. */
    suspend fun payOut(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): String? {
        val payoutTx = relayer.payout(payoutRequest(record, swap)).transactions.lastOrNull()
        chain.caughtUp(record.swapId) { it.paidOut }
        return payoutTx
    }

    suspend fun relayerFee(amount: BigInteger): BigInteger = checkedFee(relayer.terms(), amount)

    private fun checkedFee(
        terms: RelayerTerms,
        amount: BigInteger
    ): BigInteger {
        if (terms.chainId != config.chainId || Address.parse(terms.contract) != config.contract) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the relayer serves another deployment")
        }
        val fee = BigInteger(terms.fee)
        if (fee > config.maxRelayerFee || fee >= amount) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.RELAYER_FEE, "the relayer asks a fee of $fee")
        }
        return fee
    }

    private suspend fun payoutRequest(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): PayoutRequest {
        val terms = relayer.terms()
        val fee = checkedFee(terms, swap.amount)
        val note = keys.payoutNote(record.index)
        val signature =
            keys.signPayout(
                record.index,
                record.quote.chainId,
                config.contract,
                record.swapId.hexToBytes(),
                Address.parse(terms.relayer),
                fee,
            )
        return PayoutRequest(
            swapId = record.swapId,
            note = NoteJson(note.npk.hex(), note.encryptedBundle.map { it.hex() }, note.shieldKey.hex()),
            fee = fee.toString(),
            signature = signature.hex(),
        )
    }

    /**
     * Never reveals under a lock with less than [CLAIM_MARGIN_SECONDS] left: the claim must land before
     * it lapses, or the maker gets the next turn knowing both halves.
     */
    private suspend fun holdClaimLock(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        val now = chain.now()
        if (swap.claimLockUntil > now + CLAIM_MARGIN_SECONDS) return
        if (swap.claimLockUntil > now) {
            throw AtomicSwapBlockedException(
                AtomicSwapBlock.CLAIM_LOCK_LAPSING,
                "the claim lock lapses too soon to reveal under; claim once the next turn is ours",
            )
        }
        // Under the contract's lock duration, which makes one signature good for one lock only.
        val deadline = now + LOCK_SIGNATURE_TTL_SECONDS
        val signature =
            keys.signLockClaim(
                record.index,
                record.quote.chainId,
                config.contract,
                record.swapId.hexToBytes(),
                deadline,
            )
        relayer.lockClaim(LockClaimRequest(record.swapId, deadline, signature.hex()))
        chain.caughtUp(record.swapId) { it.claimLockUntil > now }
    }

    private companion object {
        const val CLAIM_MARGIN_SECONDS = 5 * 60L
        const val LOCK_SIGNATURE_TTL_SECONDS = 2 * 60L
    }
}

/** The swap once our RPC node shows what [seen] expects: it can lag the node a transaction went through. */
internal suspend fun AtomicSwapChainReader.caughtUp(
    swapId: String,
    seen: (OnChainSwap) -> Boolean
): OnChainSwap {
    repeat(CATCH_UP_POLLS) {
        swap(swapId.hexToBytes())?.takeIf(seen)?.let { return it }
        delay(CATCH_UP_INTERVAL)
    }
    throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "our RPC node never showed the swap as expected")
}

private const val CATCH_UP_POLLS = 30
private val CATCH_UP_INTERVAL = 2.seconds
