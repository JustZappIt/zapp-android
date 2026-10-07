// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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

    /** The swap the maker opened for [quoteId]. */
    suspend fun accept(
        quoteId: String,
        acceptance: SwapAcceptance
    ): SwapId

    /** A quote for [amount] of [user]'s private dollars, refunded into the note [refundNote] commits to. */
    suspend fun quoteReverse(
        amount: Usdc6,
        user: Address,
        refundNote: NoteCommitment
    ): ReverseQuote

    suspend fun acceptReverse(
        quoteId: String,
        acceptance: SwapAcceptance
    ): SwapId
}

/** A relayer, which sends the transactions of a user with no account on the chain. */
interface SwapRelayer {
    suspend fun terms(): RelayerTerms

    suspend fun fundReverse(request: ReverseFundingRequest): Sent

    suspend fun lockClaim(authorization: SwapAuthorization): Sent

    suspend fun claim(reveal: SwapReveal): Sent

    suspend fun payout(payout: SwapPayout): Sent

    suspend fun ready(authorization: SwapAuthorization): Sent

    suspend fun lockRefund(authorization: SwapAuthorization): Sent

    suspend fun refund(reveal: SwapReveal): Sent

    suspend fun refundPayout(payout: SwapPayout): Sent

    suspend fun rescue(payout: SwapRescue): Sent
}

/** The maker over HTTP, on a client that never retries: quotes are single-use, and a timed-out accept may open. */
class MakerClient(
    http: HttpClient,
    baseUrl: Url,
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
        acceptance: SwapAcceptance
    ): SwapId =
        service
            .post("/v1/quote/$quoteId/accept", acceptance, SwapAcceptance.serializer(), Accepted.serializer())
            .swapId

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
        acceptance: SwapAcceptance
    ): SwapId =
        service
            .post("/v1/reverse/quote/$quoteId/accept", acceptance, SwapAcceptance.serializer(), Accepted.serializer())
            .swapId

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

    override suspend fun lockClaim(authorization: SwapAuthorization) =
        service.send("/v1/lock-claim", authorization, SwapAuthorization.serializer())

    override suspend fun claim(reveal: SwapReveal) = service.send("/v1/claim", reveal, SwapReveal.serializer())

    override suspend fun payout(payout: SwapPayout) = service.send("/v1/payout", payout, SwapPayout.serializer())

    override suspend fun ready(authorization: SwapAuthorization) =
        service.send("/v1/reverse/ready", authorization, SwapAuthorization.serializer())

    override suspend fun lockRefund(authorization: SwapAuthorization) =
        service.send("/v1/reverse/lock-refund", authorization, SwapAuthorization.serializer())

    override suspend fun refund(reveal: SwapReveal) =
        service.send("/v1/reverse/refund", reveal, SwapReveal.serializer())

    override suspend fun refundPayout(payout: SwapPayout) =
        service.send("/v1/reverse/refund-payout", payout, SwapPayout.serializer())

    override suspend fun rescue(payout: SwapRescue) =
        service.send("/v1/reverse/rescue", payout, SwapRescue.serializer())
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
    ): T =
        read(
            exchange {
                http.post(url(path)) {
                    if (isQuick) quick()
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(request, body))
                }
            },
            answer,
            check,
        )

    private fun url(path: String) = baseUrl.toString().trimEnd('/') + path

    private fun HttpRequestBuilder.quick() =
        timeout {
            requestTimeoutMillis = QUICK_TIMEOUT.inWholeMilliseconds
            socketTimeoutMillis = QUICK_TIMEOUT.inWholeMilliseconds
        }

    private suspend fun exchange(request: suspend () -> HttpResponse): Answer =
        try {
            request().let { Answer(it.status, it.bodyAsText()) }
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
    )

    // The code stays a string here, so one this build doesn't know still leaves the message readable.
    @Serializable
    private class ServiceError(
        val code: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val ERROR_EXCERPT = 200
        val QUICK_TIMEOUT = 20.seconds
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}
