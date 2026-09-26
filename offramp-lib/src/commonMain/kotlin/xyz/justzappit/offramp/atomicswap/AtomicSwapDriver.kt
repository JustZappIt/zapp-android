// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.delay
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
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
) {
    private val claim = AtomicSwapClaim(config, relayer, chain, keys)

    /**
     * Takes a quote for [units] and accepts it with a fresh index, which counts as spent from here on.
     * The record is saved before the maker sees the share, so an accept that fails midway is still known.
     */
    suspend fun open(units: Int): AtomicSwapRecord {
        check(store.active()?.finished != false) { "a swap is already under way" }
        val index = store.takeIndex()
        val quote = maker.quote(units, keys.authAddress(index), keys.payoutNote(index).commitment)
        check(quote.chainId == config.chainId) { "the quote is for another chain" }
        check(Address.parse(quote.contract) == config.contract && Address.parse(quote.token) == config.token) {
            "the quote is for another contract or token"
        }
        val acceptance =
            keys.accept(
                index,
                quote.chainId,
                config.contract,
                quote.quoteId.hexToBytes(),
                quote.makerShare.hexToBytes(),
                quote.makerProof.hexToBytes(),
            )
        val swapId = AtomicSwapChain.swapId(Address.parse(quote.maker), acceptance.userShare)
        val record = AtomicSwapRecord(index, quote, swapId.hex(), zcashHeight = zcash.chainHeight())
        store.save(record)
        val accepted = maker.accept(quote.quoteId, acceptance)
        check(accepted.swapId.hexToBytes().contentEquals(swapId)) { "the maker reported another swap" }
        return record
    }

    /**
     * Checks the swap as the contract records it and pays the deposit to the account its shares make.
     * Nothing is paid unless every check passes, and never twice: a deposit cut short is looked up in
     * the wallet's history before paying again.
     */
    suspend fun deposit(record: AtomicSwapRecord): AtomicSwapRecord {
        if (record.depositTxId != null) return record
        val swap = chain.caughtUp(record.swapId) { true }
        val address = keys.depositAddress(record.index, swap.makerShare)
        val sent = if (record.depositAttempted) zcash.findPayment(address) else null
        val txId = sent ?: payDeposit(record, swap, address)
        return record.copy(depositAttempted = true, depositTxId = txId).also { store.save(it) }
    }

    /** One look at the chain: claims, refunds, or says what it waits for. */
    suspend fun advance(record: AtomicSwapRecord): AtomicSwapStep {
        val swap = if (record.finished) null else chain.swap(record.swapId.hexToBytes())
        return when {
            record.outcome != null -> AtomicSwapStep.Finished(record.outcome)
            swap == null -> AtomicSwapStep.Waiting("the swap is not on-chain")
            else -> step(record, swap)
        }
    }

    /** Drops a swap that never reached the chain, such as one whose accept failed. */
    suspend fun abandon(record: AtomicSwapRecord): AtomicSwapStep {
        check(chain.swap(record.swapId.hexToBytes()) == null) { "the swap is on-chain: advance it instead" }
        check(!record.depositAttempted) { "a deposit was started" }
        return finish(record, "abandoned before it opened")
    }

    private suspend fun step(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): AtomicSwapStep =
        when (swap.stage) {
            SwapStage.REFUNDED -> {
                refund(record, swap)
            }

            SwapStage.CLAIMED -> {
                if (!swap.paidOut) claim.payOut(record, swap)
                finish(record, PAID)
            }

            SwapStage.READY -> {
                claim.claim(record, swap)
                finish(record, PAID)
            }

            // A maker gone quiet past t0 no longer holds the deposit; claim it then.
            SwapStage.OPEN -> {
                if (record.depositTxId != null && chain.now() >= swap.t0) {
                    claim.claim(record, swap)
                    finish(record, PAID)
                } else {
                    AtomicSwapStep.Waiting("waiting for the maker to confirm the deposit")
                }
            }
        }

    private suspend fun payDeposit(
        record: AtomicSwapRecord,
        swap: OnChainSwap,
        address: String,
    ): String {
        verify(record, swap)
        store.save(record.copy(depositAttempted = true))
        return zcash.pay(address, record.quote.depositZat)
    }

    private suspend fun verify(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        check(swap.stage == SwapStage.OPEN) { "the swap is ${swap.stage}" }
        check(swap.makerShare.contentEquals(record.quote.makerShare.hexToBytes())) {
            "the on-chain maker share is not the quoted one"
        }
        check(swap.userShare.contentEquals(keys.userShare(record.index))) { "the on-chain user share is not ours" }
        check(
            swap.user == keys.authAddress(record.index) &&
                swap.payoutNote.contentEquals(keys.payoutNote(record.index).commitment)
        ) { "the payout goes to someone else" }
        check(swap.token == config.token && swap.amount.compareTo(BigInteger(record.quote.amount)) == 0) {
            "the payout differs from the quote"
        }
        val now = chain.now()
        check(swap.t0 in (now + config.minSecondsToT0)..(now + MAX_SECONDS_TO_T0)) {
            "t0 is ${swap.t0 - now} s away, outside ${config.minSecondsToT0}..$MAX_SECONDS_TO_T0 s"
        }
    }

    private suspend fun refund(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): AtomicSwapStep {
        val outcome =
            if (deposited(record, swap)) {
                val txId = zcash.sweepRefund(record.index, swap.makerShare, swap.secret, record.zcashHeight)
                "refunded: the deposit came home in $txId"
            } else {
                "refunded before anything was deposited"
            }
        return finish(record, outcome)
    }

    /** A deposit recorded as paid, or one cut short that the wallet's history shows went out. */
    private suspend fun deposited(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): Boolean =
        record.depositTxId != null ||
            (record.depositAttempted && zcash.findPayment(keys.depositAddress(record.index, swap.makerShare)) != null)

    private suspend fun finish(
        record: AtomicSwapRecord,
        outcome: String
    ): AtomicSwapStep {
        store.save(record.copy(outcome = outcome))
        return AtomicSwapStep.Finished(outcome)
    }

    private companion object {
        const val PAID = "paid into Railgun"

        // Until t0 an unresponsive maker holds the deposit.
        const val MAX_SECONDS_TO_T0 = 2 * 60 * 60L
    }
}

/** Claiming into Railgun through a relayer: the lock, the reveal, and the payout. */
internal class AtomicSwapClaim(
    private val config: AtomicSwapConfig,
    private val relayer: RelayerClient,
    private val chain: AtomicSwapChainReader,
    private val keys: AtomicSwapKeys,
) {
    /**
     * Reveals `z` under a claim lock with time to spare, then has the payout sent. Checks first that
     * Railgun would take it: a payout it refuses would sit in the contract, and it is better for the
     * swap to unwind, which leaves the ZEC with the user.
     */
    suspend fun claim(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        val payout = payoutRequest(record, swap)
        check(chain.railgunAccepts(swap.token)) { "Railgun is not taking the payout now; the share stays secret" }
        holdClaimLock(record, swap)
        val sent = relayer.claim(ClaimRequest(record.swapId, keys.claimSecret(record.index).hex(), payout))
        chain.caughtUp(record.swapId) { it.stage == SwapStage.CLAIMED }
        // The relayer reveals first and pays out after; a payout that failed is sent again.
        if (sent.transactions.size < 2) relayer.payout(payout)
        chain.caughtUp(record.swapId) { it.paidOut }
    }

    /** A claim revealed before an interruption: only the payout is left. */
    suspend fun payOut(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ) {
        relayer.payout(payoutRequest(record, swap))
        chain.caughtUp(record.swapId) { it.paidOut }
    }

    private suspend fun payoutRequest(
        record: AtomicSwapRecord,
        swap: OnChainSwap
    ): PayoutRequest {
        val terms = relayer.terms()
        check(terms.chainId == record.quote.chainId && Address.parse(terms.contract) == config.contract) {
            "the relayer serves another deployment"
        }
        val fee = BigInteger(terms.fee)
        check(fee <= config.maxRelayerFee && fee < swap.amount) { "the relayer asks a fee of $fee" }
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
            fee = terms.fee,
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
        check(swap.claimLockUntil <= now) {
            "the claim lock lapses too soon to reveal under; claim once the next turn is ours"
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
    error("our RPC node never showed the swap as expected")
}

private const val CATCH_UP_POLLS = 30
private val CATCH_UP_INTERVAL = 2.seconds
