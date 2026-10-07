// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.time.Duration.Companion.seconds

/** A maker's quote API, for swaps either way. */
interface SwapMaker {
    suspend fun info(): MakerInfo

    /** A quote paying [amount] to [payout], into the Railgun note [payoutNote] commits to. */
    suspend fun quote(
        amount: Usdc6,
        payout: Address,
        payoutNote: NoteCommitment
    ): SwapQuote

    /** The swap the maker opened for [quoteId], [swapId] if it took our share, and the deadlines it picked. */
    suspend fun accept(
        quoteId: String,
        swapId: SwapId,
        acceptance: SwapAcceptance
    ): SwapAccepted

    /** A quote for [amount] of [user]'s private dollars, refunded into the note [refundNote] commits to. */
    suspend fun quoteReverse(
        amount: Usdc6,
        user: Address,
        refundNote: NoteCommitment
    ): ReverseQuote

    suspend fun acceptReverse(
        quoteId: String,
        swapId: SwapId,
        acceptance: SwapAcceptance
    ): SwapAccepted

    /** Holds the token forward swap [swapId]'s accept paid with, if handed back; a failed read is tried again later. */
    suspend fun collectToken(swapId: SwapId)

    /** As [collectToken], for a reverse swap. */
    suspend fun collectReverseToken(swapId: SwapId)
}

/** A relayer, which sends the transactions of a user with no account on the chain, each with its swap's terms. */
interface SwapRelayer {
    suspend fun terms(): RelayerTerms

    suspend fun fundReverse(request: ReverseFundingRequest): Sent

    suspend fun lockClaim(
        authorization: SwapAuthorization,
        terms: SwapTerms
    ): Sent

    suspend fun claim(
        reveal: SwapReveal,
        terms: SwapTerms
    ): Sent

    suspend fun payout(
        payout: SwapPayout,
        terms: SwapTerms
    ): Sent

    suspend fun ready(
        authorization: SwapAuthorization,
        terms: SwapTerms
    ): Sent

    suspend fun lockRefund(
        authorization: SwapAuthorization,
        terms: SwapTerms
    ): Sent

    suspend fun refund(
        reveal: SwapReveal,
        terms: SwapTerms
    ): Sent

    suspend fun refundPayout(
        payout: SwapPayout,
        terms: SwapTerms
    ): Sent

    suspend fun rescue(
        rescue: SwapRescue,
        terms: SwapTerms
    ): Sent
}

/**
 * The maker over HTTP, on a client that never retries: quotes are single-use, and a timed-out accept may open. An
 * accept the maker asks a token for goes again with one from [tokens], which the swap hands back once paid into.
 */
class MakerClient(
    http: HttpClient,
    baseUrl: Url,
    private val tokens: SwapTokenSource = NO_TOKENS,
) : SwapMaker {
    private val service = SwapService(http, baseUrl, AtomicSwapService.MAKER)

    override suspend fun info(): MakerInfo = service.get("/v1/info", MakerInfo.serializer())

    override suspend fun quote(
        amount: Usdc6,
        payout: Address,
        payoutNote: NoteCommitment
    ): SwapQuote =
        service.post(
            "/v1/quote",
            QuoteRequest(amount.units(), payout, payoutNote),
            QuoteRequest.serializer(),
            SwapQuote.serializer(),
            isQuick = true,
        ) { it.requireWellFormed() }

    override suspend fun accept(
        quoteId: String,
        swapId: SwapId,
        acceptance: SwapAcceptance
    ): SwapAccepted = service.accept("/v1/quote/$quoteId/accept", swapId, acceptance, tokens)

    override suspend fun quoteReverse(
        amount: Usdc6,
        user: Address,
        refundNote: NoteCommitment
    ): ReverseQuote =
        service.post(
            "/v1/reverse/quote",
            ReverseQuoteRequest(amount.units(), user, refundNote),
            ReverseQuoteRequest.serializer(),
            ReverseQuote.serializer(),
            isQuick = true,
        ) { it.requireWellFormed() }

    override suspend fun acceptReverse(
        quoteId: String,
        swapId: SwapId,
        acceptance: SwapAcceptance
    ): SwapAccepted = service.accept("/v1/reverse/quote/$quoteId/accept", swapId, acceptance, tokens)

    override suspend fun collectToken(swapId: SwapId) = collect(swapId, "/v1/swaps/${swapId.hex}")

    override suspend fun collectReverseToken(swapId: SwapId) = collect(swapId, "/v1/reverse/swaps/${swapId.hex}")

    // Read only while a token is awaited back, and again at the swap's next step if the maker didn't answer: everything
    // else about the swap is read from the chain.
    private suspend fun collect(
        swapId: SwapId,
        path: String
    ) {
        if (!tokens.awaitsReturn(swapId)) return
        val status =
            try {
                service.get(path, MakerSwapStatus.serializer())
            } catch (_: AtomicSwapHttpException) {
                return
            }
        tokens.collect(swapId, status.tokenReturn)
    }

    // The maker counts a quote's amount in `u32` token base units.
    private fun Usdc6.units(): Long {
        require(micros.signum() > 0 && micros.bitLength() <= Int.SIZE_BITS) { "an amount the maker can't quote" }
        return micros.toLong()
    }
}

/** A relayer over HTTP. Must not retry either: a request that timed out may have sent its transaction. */
class RelayerClient(
    http: HttpClient,
    baseUrl: Url,
) : SwapRelayer {
    private val service = SwapService(http, baseUrl, AtomicSwapService.RELAYER)

    override suspend fun terms(): RelayerTerms =
        service.get("/v1/terms", RelayerTerms.serializer()) { it.requireWellFormed() }

    override suspend fun fundReverse(request: ReverseFundingRequest): Sent =
        service.post("/v1/reverse/fund", request, ReverseFundingRequest.serializer(), Sent.serializer()) {
            require(it.transactions.size <= 1) { "funding returned multiple transactions" }
        }

    override suspend fun lockClaim(
        authorization: SwapAuthorization,
        terms: SwapTerms
    ) = service.send("/v1/lock-claim", authorization.request(terms), AuthorizationRequest.serializer())

    override suspend fun claim(
        reveal: SwapReveal,
        terms: SwapTerms
    ) = service.send("/v1/claim", reveal.request(terms), RevealRequest.serializer())

    override suspend fun payout(
        payout: SwapPayout,
        terms: SwapTerms
    ) = service.send("/v1/payout", payout.request(terms), PayoutRequest.serializer())

    override suspend fun ready(
        authorization: SwapAuthorization,
        terms: SwapTerms
    ) = service.send("/v1/reverse/ready", authorization.request(terms), AuthorizationRequest.serializer())

    override suspend fun lockRefund(
        authorization: SwapAuthorization,
        terms: SwapTerms
    ) = service.send("/v1/reverse/lock-refund", authorization.request(terms), AuthorizationRequest.serializer())

    override suspend fun refund(
        reveal: SwapReveal,
        terms: SwapTerms
    ) = service.send("/v1/reverse/refund", reveal.request(terms), RevealRequest.serializer())

    override suspend fun refundPayout(
        payout: SwapPayout,
        terms: SwapTerms
    ) = service.send("/v1/reverse/refund-payout", payout.request(terms), PayoutRequest.serializer())

    override suspend fun rescue(
        rescue: SwapRescue,
        terms: SwapTerms
    ) = service.send("/v1/reverse/rescue", rescue.request(terms), RescueRequest.serializer())
}

private suspend fun <T> SwapService.send(
    path: String,
    request: T,
    serializer: KSerializer<T>
): Sent = post(path, request, serializer, Sent.serializer())

/** One swap service over HTTP, whose failures are all [AtomicSwapHttpException]s naming it. */
internal class SwapService(
    private val http: HttpClient,
    private val baseUrl: Url,
    private val service: AtomicSwapService,
) {
    /** A read, which gives up sooner than a request that may be sending a transaction. */
    suspend fun <T> get(
        path: String,
        answer: KSerializer<T>,
        check: (T) -> Unit = {},
    ): T = read(exchange { http.get(url(path)) { quick() } }, answer, check)

    suspend fun <B, T> post(
        path: String,
        body: B,
        request: KSerializer<B>,
        answer: KSerializer<T>,
        isQuick: Boolean = false,
        check: (T) -> Unit = {},
    ): T = read(deliver(path, json.encodeToString(request, body), isQuick, authorization = null), answer, check)

    /**
     * An accept, which the maker may ask a token for: then it goes again with one from [tokens] and a request for it
     * back, and with the next token while the maker refuses the one sent, [PAID_ATTEMPTS] times at most.
     */
    suspend fun accept(
        path: String,
        swapId: SwapId,
        acceptance: SwapAcceptance,
        tokens: SwapTokenSource,
    ): SwapAccepted {
        val unpaid = json.encodeToString(SwapAcceptance.serializer(), acceptance)
        var answer = deliver(path, unpaid, isQuick = false, authorization = null)
        var paid = 0
        while (paid < PAID_ATTEMPTS && answer.status == HttpStatusCode.Unauthorized) {
            val challenge = answer.challenge ?: break
            val payment = tokens.pay(swapId, challenge)
            val body = json.encodeToString(SwapAcceptance.serializer(), acceptance.copy(tokenRequest = payment.request))
            answer =
                try {
                    deliver(path, body, isQuick = false, payment.authorization)
                } catch (e: AtomicSwapHttpException.Unreachable) {
                    tokens.settle(payment, status = null)
                    throw e
                }
            tokens.settle(payment, answer.status)
            paid++
        }
        return read(answer, SwapAccepted.serializer()) {}
    }

    private suspend fun deliver(
        path: String,
        payload: String,
        isQuick: Boolean,
        authorization: String?,
    ): Answer =
        exchange {
            http.post(url(path)) {
                if (isQuick) quick()
                authorization?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }

    private fun url(path: String) = baseUrl.toString().trimEnd('/') + path

    private fun HttpRequestBuilder.quick() =
        timeout {
            requestTimeoutMillis = QUICK_TIMEOUT.inWholeMilliseconds
            socketTimeoutMillis = QUICK_TIMEOUT.inWholeMilliseconds
        }

    private suspend fun exchange(request: suspend () -> HttpResponse): Answer =
        try {
            request().let { Answer(it.status, it.bodyAsText(), it.headers[HttpHeaders.WWWAuthenticate]) }
        } catch (e: IOException) {
            throw AtomicSwapHttpException.Unreachable(service, e)
        }

    // A body that doesn't read, or that [check] turns down, is the service's unreadable answer.
    private fun <T> read(
        answer: Answer,
        serializer: KSerializer<T>,
        check: (T) -> Unit,
    ): T {
        if (!answer.status.isSuccess()) throw refusal(answer)
        return try {
            json.decodeFromString(serializer, answer.body).also(check)
        } catch (e: IllegalArgumentException) {
            throw AtomicSwapHttpException.Unreadable(service, e)
        }
    }

    private fun refusal(answer: Answer): AtomicSwapHttpException.Refused {
        val error =
            try {
                json.decodeFromString(ServiceError.serializer(), answer.body)
            } catch (_: IllegalArgumentException) {
                null
            }
        val reason = error?.error ?: answer.body.take(ERROR_EXCERPT)
        val code = error?.code?.let(SwapErrorCode::named)
        return AtomicSwapHttpException.Refused(service, answer.status.value, code, reason)
    }

    private class Answer(
        val status: HttpStatusCode,
        val body: String,
        /** RFC 9577's challenge, on a refusal that a token would pay for. */
        val challenge: String?,
    )

    // The code stays a string here, so one this build doesn't know still leaves the message readable.
    @Serializable
    private class ServiceError(
        val code: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val ERROR_EXCERPT = 200
        const val PAID_ATTEMPTS = 3
        val QUICK_TIMEOUT = 20.seconds
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}
