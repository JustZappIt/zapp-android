// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Posts kept requests, as zecswap-client's `Depositor::post_shield` does, and keeps each answer before acting on it.
 * A request is always kept before it's posted, and what goes is always the bytes kept: the relayer never sends one
 * twice, and answers the same request with what it sent for it.
 */
internal class DepositPosting(
    private val pin: DepositsPin,
    private val relayer: DepositsRelayer,
    private val chain: DepositsChainReader,
    private val store: DepositStore,
    private val signing: DepositSigning,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    /**
     * Posts [record]'s kept bytes. A refused shield is signed again once, from the terms and the balance read now, and
     * that is posted; refused again, it's dropped.
     */
    suspend fun postKept(
        record: DepositRecord,
        context: PassContext,
    ) {
        var next: DepositRecord? = record
        while (next != null) {
            next = answered(next, post(next), context)
        }
    }

    // Keeps what [answer] says of [record], and gives the request to post next: one signed again after a refusal.
    private suspend fun answered(
        record: DepositRecord,
        answer: DepositAnswer,
        context: PassContext,
    ): DepositRecord? {
        val now = now()
        // One the relayer named a transaction for stays named, whatever it answers now: it's never signed again while
        // that may land, and only the chain, or the authorization running out, ends it.
        val isNamed = record.state == DepositState.SENT
        when {
            answer is DepositAnswer.Sent -> {
                keep(record.named(answer.transactions, conflict = false, now))
            }

            answer is DepositAnswer.Conflict && answer.transactions.isNotEmpty() -> {
                keep(record.named(answer.transactions, conflict = true, now))
            }

            answer is DepositAnswer.Conflict -> {
                unnamedConflict(if (isNamed) record.askedAgain(now) else record.unanswered(null, waiting = false, now))
            }

            isNamed -> {
                keep(record.askedAgain(now))
            }

            answer is DepositAnswer.Retry -> {
                keep(record.unanswered(answer.after, answer.isWaiting, now))
            }

            answer is DepositAnswer.Refused -> {
                return refused(record, answer, context)
            }
        }
        return null
    }

    private suspend fun refused(
        record: DepositRecord,
        refusal: DepositAnswer.Refused,
        context: PassContext,
    ): DepositRecord? {
        log("the relayer refused the ${record.kind.name.lowercase()} of address ${record.index}: ${refusal.reason}")
        val fresh =
            if (record.kind == DepositKind.SHIELD && !record.afterRefusal) {
                attempt(log, "address ${record.index} wasn't signed for again") { signAfterRefusal(record, context) }
            } else {
                null
            }
        val ended = record.ended(if (fresh == null) DepositState.REFUSED else DepositState.REPLACED, now())
        store.keep(listOfNotNull(ended, fresh))
        return fresh
    }

    // From the terms and the balance read now; none where the address no longer holds the minimum, or the relayer's
    // terms aren't the pin's.
    private suspend fun signAfterRefusal(
        record: DepositRecord,
        context: PassContext,
    ): DepositRecord? {
        val read = if (context.mayPost) relayer.terms().depositTerms(pin) else null
        val terms = (read as? DepositTermsRead.Pinned)?.terms
        val balance = terms?.let { chain.uses(listOf(record.from)).single().balance }
        return if (terms != null && balance != null && balance >= terms.minValue) {
            signing.shield(record.index, balance, terms, afterRefusal = true, after = record.expiresAt)
        } else {
            null
        }
    }

    // A 409 naming no transaction: the authorization is used, or another deposit of the address is on its way. The
    // chain tells which. A used nonce is this request landed, since only the Deposits contract can use it, and only for
    // this note, fee and relayer; a balance under its value means another deposit took its funds, so it never can.
    // Otherwise it waits, posted again later.
    private suspend fun unnamedConflict(record: DepositRecord) {
        keep(record)
        val isUsed =
            when (record.kind) {
                DepositKind.SHIELD -> chain.authorizationUsed(record.from, checkNotNull(record.nonce).hexToBytes())
                DepositKind.RESCUE -> chain.rescueNonce(record.from) > checkNotNull(record.rescue).nonce
            }
        val isTaken =
            !isUsed &&
                record.kind == DepositKind.SHIELD &&
                chain.uses(listOf(record.from)).single().balance < record.value.micros
        val now = now()
        if (isUsed) {
            val shielded = Usdc6(record.value.micros - record.fee)
            keep(record.landed(DepositLanding(null, shielded, Usdc6(record.fee), now), now))
        } else if (isTaken) {
            keep(record.ended(DepositState.REPLACED, now))
        }
    }

    private suspend fun post(record: DepositRecord): DepositAnswer =
        record.shield?.let { relayer.shield(it) } ?: relayer.rescue(checkNotNull(record.rescue))

    private suspend fun keep(record: DepositRecord) = store.keep(listOf(record))
}

/**
 * Follows what's under way: a named transaction until the chain settles it, a request nothing named until it's posted
 * again or signed again. Only the chain settles a deposit, through a mined receipt in which the pinned contract's event
 * names the address and one of this wallet's notes: the relayer's word alone is believed for nothing.
 */
internal class DepositFollowing(
    private val store: DepositStore,
    private val chain: DepositsChainReader,
    private val signing: DepositSigning,
    private val posting: DepositPosting,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    /** Moves [record] on, a request kept and not finished: one named is followed only while the chain reads. */
    suspend fun advance(
        record: DepositRecord,
        context: PassContext,
        isChainRead: Boolean,
    ) {
        when (record.state) {
            DepositState.SENT -> if (isChainRead) follow(record, context)
            DepositState.SIGNED -> resend(record, context)
            else -> Unit
        }
    }

    /**
     * [record]'s last named transaction: mined and believed, it landed; mined and not believed, the bytes go again
     * later; reverted, the address waits an hour; gone past the authorization, it expired; not mined within a minute,
     * the same bytes go again, and the relayer names what sends them now.
     */
    private suspend fun follow(
        record: DepositRecord,
        context: PassContext,
    ) {
        val transaction = record.transactions.lastOrNull()
        val status = transaction?.let { chain.status(it) }
        val now = now()
        when {
            transaction == null -> {
                keep(record.unanswered(null, waiting = false, now))
            }

            status == TransactionStatus.CONFIRMED -> {
                believe(record, transaction, now)
            }

            status == TransactionStatus.REVERTED -> {
                log("the ${record.kind.name.lowercase()} of address ${record.index} reverted")
                keep(record.ended(DepositState.REVERTED, now))
            }

            status == TransactionStatus.UNKNOWN && now > record.expiresAt + CLOCK_SLACK_SECONDS -> {
                keep(record.ended(DepositState.EXPIRED, now))
            }

            context.mayPost && now - (record.postedAt ?: 0) >= REPOST_SECONDS -> {
                posting.postKept(record, context)
            }
        }
    }

    /**
     * A kept request nothing is named for: posted again once its wait is over, or first signed again where its
     * validity or the fee's honor window went stale. Only a shield is signed again by itself: a rescue is the user's.
     */
    private suspend fun resend(
        record: DepositRecord,
        context: PassContext,
    ) {
        val now = now()
        val isExpired = now > record.expiresAt + CLOCK_SLACK_SECONDS
        val isStale =
            record.kind == DepositKind.SHIELD &&
                (record.expiresAt < now + RESIGN_BEFORE_SECONDS || record.terms.feeExpiresAt?.let { now >= it } == true)
        val balance = context.signable(record.index)?.takeIf { isStale && context.mayPost }
        when {
            balance != null -> signAgain(record, balance, context)
            isExpired -> keep(record.ended(DepositState.EXPIRED, now))
            isStale || !context.mayPost || (record.retryAt ?: 0) > now -> Unit
            else -> posting.postKept(record, context)
        }
    }

    private suspend fun signAgain(
        record: DepositRecord,
        balance: BigInteger,
        context: PassContext,
    ) {
        val terms = checkNotNull(context.terms)
        val fresh = signing.shield(record.index, balance, terms, record.afterRefusal, after = record.expiresAt)
        store.keep(listOf(record.ended(DepositState.REPLACED, now()), fresh))
        posting.postKept(fresh, context)
    }

    private suspend fun believe(
        record: DepositRecord,
        transaction: TxHash,
        now: Long,
    ) {
        val landing = landing(record, transaction, now)
        if (landing != null) {
            keep(record.landed(landing, now))
        } else {
            log("a transaction named for the ${record.kind.name.lowercase()} of address ${record.index} isn't it")
            keep(record.unanswered(null, waiting = false, now).copy(transactions = emptyList(), conflict = false))
        }
    }

    // The pinned contract's event of [record]'s kind in [transaction], naming its address, with its note, or after a
    // 409 with any note the address's kept requests of that kind pay.
    private suspend fun landing(
        record: DepositRecord,
        transaction: TxHash,
        now: Long,
    ): DepositLanding? {
        val own =
            if (record.conflict) {
                store.records().filter { it.index == record.index && it.kind == record.kind }.map { it.commitment }
            } else {
                listOf(record.commitment)
            }
        return chain
            .events(transaction)
            .firstOrNull { it.kind == record.kind && it.from == record.from && it.note in own }
            ?.let { DepositLanding(transaction, Usdc6(it.shielded), Usdc6(it.fee), now) }
    }

    private suspend fun keep(record: DepositRecord) = store.keep(listOf(record))
}
