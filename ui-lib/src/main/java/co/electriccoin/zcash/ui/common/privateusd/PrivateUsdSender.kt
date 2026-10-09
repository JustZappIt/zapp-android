// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployments
import co.electriccoin.zcash.ui.common.backgroundScope
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.RailgunBroadcast
import xyz.justzappit.offramp.atomicswap.RailgunSendsClient
import xyz.justzappit.railgun.RailgunBroadcaster
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunRelayRequest
import xyz.justzappit.railgun.RailgunRelayedProof
import xyz.justzappit.railgun.RailgunTransfer
import java.math.BigInteger
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class PrivateUsdSendRequest(
    val token: PrivateUsdToken,
    val amount: BigInteger,
    val to: RailgunDestination,
) {
    val isWithdrawal: Boolean get() = to is RailgunDestination.Public
}

data class PrivateUsdSendCost(
    /** Railgun's fee on a withdrawal, taken from the amount sent. */
    val railgunFee: BigInteger,
    val feeBasisPoints: Int,
    /** The broadcaster's fee for sending it, paid from the private balance on top of the amount. */
    val networkFee: BigInteger,
    val networkFeeToken: Address,
    /** Until when the relayer holds [networkFee]'s gas rate, in Unix seconds; null when it's the minimum. */
    val networkFeeExpiresAt: Long? = null,
) {
    fun received(amount: BigInteger): BigInteger = amount - railgunFee
}

sealed interface PrivateUsdSendOutcome {
    /** In a block. */
    data class Sent(
        val txHash: TxHash
    ) : PrivateUsdSendOutcome

    /** Handed over but not seen in a block: it may still land, so it is never proved again. */
    data class Unconfirmed(
        /** The broadcaster's transaction, once it named one. */
        val txHash: TxHash?
    ) : PrivateUsdSendOutcome

    /** Nothing left the wallet. */
    data object NotSent : PrivateUsdSendOutcome

    /** An earlier payment is unresolved; no additional transaction was created. */
    data object Busy : PrivateUsdSendOutcome

    /** Nothing left the wallet: the relayer now asks [cost]'s higher network fee, which is confirmed first. */
    data class Repriced(
        val cost: PrivateUsdSendCost
    ) : PrivateUsdSendOutcome
}

/** Sends from the Railgun balance. */
interface PrivateUsdSender {
    /** What [request] costs at the network fee the relayer quotes for it now. */
    suspend fun cost(request: PrivateUsdSendRequest): PrivateUsdSendCost

    /** The least a send pays the broadcaster, on top of what it sends, in [networkFeeToken]. */
    suspend fun minNetworkFee(): BigInteger

    val networkFeeToken: Address

    /**
     * Sends [request] paying [cost]'s network fee, or a lower one the relayer quotes now; a higher one comes back to be
     * confirmed. Runs to its end, and into the send log, even once its caller stops waiting.
     */
    suspend fun send(
        request: PrivateUsdSendRequest,
        cost: PrivateUsdSendCost
    ): PrivateUsdSendOutcome

    /** Settles what the log still holds unconfirmed: confirmed, dropped, or asked about again. */
    fun reconcile()

    /** Stops every send and settlement, and waits until they have, for a wallet about to be wiped. */
    suspend fun reset()
}

class PrivateUsdSendsUnavailableException : IllegalStateException("the relayer sends no private payments now")

/** Sends through the deployment's relayer, which pays the gas for a fee note, so no account of the wallet's shows. */
class RelayedPrivateUsdSender(
    private val railgunWalletRepository: RailgunWalletRepository,
    relayer: PrivateUsdRelayer,
    chain: PrivateUsdSendChain,
    private val pin: RailgunSendsPin,
    private val sendLog: PrivateUsdSendLog,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val spendGuard: PrivateUsdSpendGuard,
) : PrivateUsdSender {
    private val pricing = PrivateUsdSendPricing(railgunWalletRepository, relayer, clock)
    private val settlement = PrivateUsdSendSettlement(relayer, chain, sendLog, clock)

    override suspend fun cost(request: PrivateUsdSendRequest): PrivateUsdSendCost = pricing.quote(request).cost

    override suspend fun minNetworkFee(): BigInteger = pricing.broadcaster().minFee

    override val networkFeeToken: Address get() = pin.feeToken

    override suspend fun send(
        request: PrivateUsdSendRequest,
        cost: PrivateUsdSendCost
    ): PrivateUsdSendOutcome = scope.async { spendGuard.send { sendNow(request, cost) } }.await()

    override fun reconcile() {
        scope.launch { settlement.settleAll() }
    }

    override suspend fun reset() {
        val work = scope.coroutineContext.job
        work.cancelChildren()
        work.children.forEach { it.join() }
    }

    // Nothing is proved for a fee above the one confirmed.
    private suspend fun sendNow(
        request: PrivateUsdSendRequest,
        confirmed: PrivateUsdSendCost
    ): PrivateUsdSendOutcome {
        val quote = attempt("nothing was sent") { pricing.held(request, confirmed) }
        return when {
            quote == null -> PrivateUsdSendOutcome.NotSent
            quote.fee > confirmed.networkFee -> PrivateUsdSendOutcome.Repriced(quote.cost)
            else -> sendQuoted(request, quote)
        }
    }

    private suspend fun sendQuoted(
        request: PrivateUsdSendRequest,
        quote: PrivateUsdSendQuote
    ): PrivateUsdSendOutcome {
        val pending =
            PrivateUsdPendingSend(
                id = UUID.randomUUID().toString(),
                token = request.token.address,
                amount = request.amount,
                to = request.to,
                startedAt = clock.now().epochSeconds,
            )
        noted { sendLog.begin(pending) }
        val proof = proveAndKeep(pending, request, quote) ?: return PrivateUsdSendOutcome.NotSent
        val outcome = settlement.deliver(pending.id, proof.request) ?: refused(request, quote.fee)
        // The balance follows the send without the send waiting for it.
        scope.launch { bestEffort("Private USD: no sync after the send") { railgunWalletRepository.sync() } }
        return outcome
    }

    // A refusal sent nothing. A fee the relayer now asks above the one proved is the user's to confirm.
    private suspend fun refused(
        request: PrivateUsdSendRequest,
        proved: BigInteger
    ): PrivateUsdSendOutcome =
        attempt("no quote after a refusal") { pricing.quote(request) }
            ?.takeIf { it.fee > proved }
            ?.let { PrivateUsdSendOutcome.Repriced(it.cost) }
            ?: PrivateUsdSendOutcome.NotSent

    // Null when nothing was proved, or what was couldn't be kept to post again: then nothing goes out.
    private suspend fun proveAndKeep(
        pending: PrivateUsdPendingSend,
        request: PrivateUsdSendRequest,
        quote: PrivateUsdSendQuote,
    ): RailgunRelayedProof? {
        val proof =
            attempt("nothing was sent") {
                railgunWalletRepository
                    .prove(request.transfer, quote.broadcaster, quote.fee)
                    .also { sendLog.prove(pending, it, clock.now().epochSeconds) }
            }
        if (proof == null) noted { sendLog.remove(pending) }
        return proof
    }
}

/** A network fee the relayer asks, and the terms it was worked out from, which a proof paying it is made for. */
private class PrivateUsdSendQuote(
    val broadcaster: RailgunBroadcaster,
    val cost: PrivateUsdSendCost,
) {
    val fee: BigInteger get() = cost.networkFee
}

/** Quotes the relayer's fee for a send from its terms now, with the SDK's own estimate of the send's gas. */
private class PrivateUsdSendPricing(
    private val railgunWalletRepository: RailgunWalletRepository,
    private val relayer: PrivateUsdRelayer,
    private val clock: Clock,
) {
    suspend fun broadcaster(): RailgunBroadcaster = relayer.broadcaster() ?: throw PrivateUsdSendsUnavailableException()

    suspend fun quote(request: PrivateUsdSendRequest): PrivateUsdSendQuote = quote(request, broadcaster())

    /** [confirmed] while the relayer will still hold it once a proof is made, and its minimum isn't above it. */
    suspend fun held(
        request: PrivateUsdSendRequest,
        confirmed: PrivateUsdSendCost
    ): PrivateUsdSendQuote {
        val broadcaster = broadcaster()
        val expiresAt = confirmed.networkFeeExpiresAt
        val isHeld =
            if (expiresAt == null) {
                broadcaster.feePerUnitGas == null
            } else {
                clock.now().epochSeconds + PROVING_TIME.inWholeSeconds < expiresAt
            }
        return if (isHeld && broadcaster.minFee <= confirmed.networkFee) {
            PrivateUsdSendQuote(broadcaster, confirmed)
        } else {
            quote(request, broadcaster)
        }
    }

    private suspend fun quote(
        request: PrivateUsdSendRequest,
        broadcaster: RailgunBroadcaster
    ): PrivateUsdSendQuote {
        val fee = railgunWalletRepository.broadcasterFee(request.transfer, broadcaster)
        val fees = if (request.isWithdrawal) railgunWalletRepository.fees() else null
        val cost =
            PrivateUsdSendCost(
                railgunFee = fees?.unshieldFee(request.amount) ?: BigInteger.ZERO,
                feeBasisPoints = fees?.unshieldBasisPoints ?: 0,
                networkFee = fee,
                networkFeeToken = broadcaster.feeToken,
                networkFeeExpiresAt = broadcaster.feeExpiresAt,
            )
        return PrivateUsdSendQuote(broadcaster, cost)
    }

    private companion object {
        val PROVING_TIME = 2.minutes
    }
}

/**
 * Settles the log's unconfirmed sends, one pass at a time. The broadcaster is asked again with the same bytes, which
 * name the same send; the chain says whether it landed.
 */
private class PrivateUsdSendSettlement(
    private val relayer: PrivateUsdRelayer,
    private val chain: PrivateUsdSendChain,
    private val sendLog: PrivateUsdSendLog,
    private val clock: Clock,
) {
    private val lock = Mutex()

    /** Posts [id]'s request and waits a while for its block; null when the relayer refused it, so nothing went. */
    suspend fun deliver(
        id: String,
        request: RailgunRelayRequest
    ): PrivateUsdSendOutcome? =
        when (val answer = post(id, request)) {
            is RailgunBroadcast.Sent -> landing(id, answer.txHash)
            is RailgunBroadcast.Refused -> null
            is RailgunBroadcast.Spent -> PrivateUsdSendOutcome.Unconfirmed(answer.transactions.firstOrNull())
            is RailgunBroadcast.Retry -> PrivateUsdSendOutcome.Unconfirmed(null)
        }

    // A pass asked for while one runs has nothing left to do.
    suspend fun settleAll() {
        if (!lock.tryLock()) return
        try {
            sendLog.unconfirmed().forEach { settle(it) }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun landing(
        id: String,
        txHash: TxHash
    ): PrivateUsdSendOutcome =
        when (withTimeoutOrNull(CONFIRM_TIMEOUT) { awaitBlock(txHash) }) {
            TransactionStatus.CONFIRMED -> {
                noted { sendLog.update(id) { it.landed(txHash) } }
                PrivateUsdSendOutcome.Sent(txHash)
            }

            TransactionStatus.REVERTED -> {
                noted { sendLog.remove(id) }
                PrivateUsdSendOutcome.NotSent
            }

            else -> {
                PrivateUsdSendOutcome.Unconfirmed(txHash)
            }
        }

    private suspend fun settle(send: PrivateUsdSendRecord) {
        bestEffort("Private USD: ${send.id} wasn't settled") {
            val relay = checkNotNull(send.relay) { "an unconfirmed send keeps its request" }
            val now = clock.now().epochSeconds
            val spentAt = relay.spentAt
            when {
                spentAt != null -> settleSpent(send.id, relay, now - spentAt)
                send.txHash != null -> settleTransaction(send.id, send.txHash, relay, now)
                else -> post(send.id, relay.request)
            }
        }
    }

    // A reverted send moved nothing. One still out is asked about again after a while: the same bytes answer with the
    // transaction now sending them, which is a new one if the first was forgotten.
    private suspend fun settleTransaction(
        id: String,
        txHash: TxHash,
        relay: PrivateUsdRelay,
        now: Long
    ) {
        when (chain.status(txHash)) {
            TransactionStatus.CONFIRMED -> {
                sendLog.update(id) { it.landed(txHash) }
            }

            TransactionStatus.REVERTED -> {
                sendLog.remove(id)
            }

            TransactionStatus.PENDING, TransactionStatus.UNKNOWN -> {
                if (now - (relay.postedAt ?: 0) >= ASK_AGAIN_AFTER.inWholeSeconds) post(id, relay.request)
            }
        }
    }

    // Its notes are spent, by a transaction of the relayer's it named or by one it doesn't know. One of those that
    // succeeded means it landed. Otherwise the nullifiers tell: all spent means it landed in some transaction; some
    // means another proof took part of its notes, and it never can; none means the relayer's transaction is still out,
    // and the relayer looks again when asked after a while.
    private suspend fun settleSpent(
        id: String,
        relay: PrivateUsdRelay,
        since: Long
    ) {
        val landed = relay.spentIn.firstOrNull { chain.status(it) == TransactionStatus.CONFIRMED }
        if (landed != null) {
            sendLog.update(id) { it.landed(landed) }
        } else {
            when (chain.spent(relay.spends)) {
                PrivateUsdNullifiers.ALL -> sendLog.update(id) { it.landed(null) }
                PrivateUsdNullifiers.SOME -> sendLog.remove(id)
                PrivateUsdNullifiers.NONE -> if (since >= SPENT_RECHECK_AFTER.inWholeSeconds) post(id, relay.request)
            }
        }
    }

    // What the answer says is logged before it's acted on; a refusal forgets the send, since nothing from it went or
    // ever will.
    private suspend fun post(
        id: String,
        request: RailgunRelayRequest
    ): RailgunBroadcast {
        val answer =
            runCatching { relayer.send(request) }.getOrElse { e ->
                if (e is CancellationException) throw e
                RailgunBroadcast.Retry(e.message.orEmpty())
            }
        val now = clock.now().epochSeconds
        when (answer) {
            is RailgunBroadcast.Sent -> {
                noted { sendLog.update(id) { it.submitted(answer.txHash, now) } }
            }

            is RailgunBroadcast.Refused -> {
                Twig.warn { "Private USD: the relayer refused a send: ${answer.reason}" }
                noted { sendLog.remove(id) }
            }

            is RailgunBroadcast.Spent -> {
                noted { sendLog.update(id) { it.spent(now, answer.transactions) } }
            }

            is RailgunBroadcast.Retry -> {
                Twig.warn { "Private USD: the relayer's answer is unknown: ${answer.reason}" }
                noted { sendLog.update(id) { it.posted(now) } }
            }
        }
        return answer
    }

    private suspend fun awaitBlock(txHash: TxHash): TransactionStatus {
        while (true) {
            val status = runCatching { chain.status(txHash) }.getOrNull()
            if (status == TransactionStatus.CONFIRMED || status == TransactionStatus.REVERTED) return status
            delay(CONFIRM_POLL)
        }
    }

    private companion object {
        val CONFIRM_TIMEOUT = 2.minutes
        val CONFIRM_POLL = 4.seconds
        val ASK_AGAIN_AFTER = 3.minutes
        val SPENT_RECHECK_AFTER = 15.minutes
    }
}

// The log is a record, not a gate, once a send is proved and kept: what follows goes ahead regardless.
private suspend fun noted(block: suspend () -> Unit) {
    bestEffort("Private USD: the send log wasn't updated", block)
}

// Only this send's own cancellation stops it; the engine's, in a reset, is a failure.
private suspend fun <T> attempt(
    what: String,
    block: suspend () -> T
): T? =
    runCatching { block() }.getOrElse { e ->
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        Twig.warn(e) { "Private USD: $what" }
        null
    }

private val PrivateUsdSendRequest.transfer: RailgunTransfer get() = RailgunTransfer(to, token.address, amount)

/** The sender this build has: its deployment's relayer, where the deployment pins one for sends. */
class PrivateUsdSenders(
    railgunWalletRepository: RailgunWalletRepository,
    deployments: AtomicSwapDeployments,
    http: HttpClient,
    sendLog: PrivateUsdSendLog,
    spendGuard: PrivateUsdSpendGuard,
) {
    val current: PrivateUsdSender? =
        deployments.current
            ?.takeIf { it.railgunNetwork == railgunWalletRepository.state.value.network }
            ?.let { deployment ->
                deployment.railgunSends?.let { pin ->
                    RelayedPrivateUsdSender(
                        railgunWalletRepository = railgunWalletRepository,
                        relayer = PrivateUsdRelayer(RailgunSendsClient(http, deployment.swap.relayerUrl), pin),
                        chain =
                            PrivateUsdSendChain(
                                BaseRpcClient(RpcHttpClient.create(), deployment.swap.rpcUrl.toString()),
                                pin.railgunProxy,
                            ),
                        pin = pin,
                        sendLog = sendLog,
                        scope = backgroundScope("Private USD sends"),
                        clock = Clock.System,
                        spendGuard = spendGuard,
                    )
                }
            }
}
