// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex

class AtomicSwapHttpException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The maker's quote API. The client must not retry: a quote is single-use, and an accept that timed
 * out may still have opened the swap. Give it a timeout of minutes, since an accept waits for the
 * maker's `open` to land.
 */
class MakerClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    suspend fun quote(
        units: Int,
        payout: Address,
        payoutNote: ByteArray
    ): SwapQuote =
        httpClient
            .post(baseUrl.trimEnd('/') + "/v1/quote") {
                contentType(ContentType.Application.Json)
                val request = QuoteRequest(units, payout.lowercaseHex, payoutNote.hex())
                setBody(json.encodeToString(QuoteRequest.serializer(), request))
            }.decode("maker", SwapQuote.serializer())

    internal suspend fun accept(
        quoteId: String,
        acceptance: UserAcceptance
    ): Accepted {
        val body = AcceptRequest(acceptance.userShare.hex(), acceptance.userProof.hex(), acceptance.viewingKeys.hex())
        return httpClient
            .post(baseUrl.trimEnd('/') + "/v1/quote/$quoteId/accept") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AcceptRequest.serializer(), body))
            }.decode("maker", Accepted.serializer())
    }
}

/** A relayer, which sends the transactions of a user with no account on the chain. Must not retry either. */
class RelayerClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    internal suspend fun terms(): RelayerTerms =
        httpClient.get(baseUrl.trimEnd('/') + "/v1/terms").decode("relayer", RelayerTerms.serializer())

    internal suspend fun lockClaim(request: LockClaimRequest): Sent =
        post("/v1/lock-claim", request, LockClaimRequest.serializer())

    internal suspend fun claim(request: ClaimRequest): Sent = post("/v1/claim", request, ClaimRequest.serializer())

    internal suspend fun payout(request: PayoutRequest): Sent = post("/v1/payout", request, PayoutRequest.serializer())

    private suspend fun <T> post(
        path: String,
        request: T,
        serializer: KSerializer<T>
    ): Sent =
        httpClient
            .post(baseUrl.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(serializer, request))
            }.decode("relayer", Sent.serializer())
}

private val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

internal fun ByteArray.hex() = "0x" + toHex()

/** The body as [serializer], or the service's `{"error": …}` as an [AtomicSwapHttpException]. */
private suspend fun <T> HttpResponse.decode(
    service: String,
    serializer: KSerializer<T>
): T {
    val text = bodyAsText()
    if (!status.isSuccess()) {
        val reason =
            runCatching {
                json
                    .parseToJsonElement(text)
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
        val detail = reason ?: text.take(ERROR_EXCERPT)
        throw AtomicSwapHttpException("$service answered ${status.value}: $detail", status.value)
    }
    return try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw AtomicSwapHttpException("$service sent an unreadable answer: ${e.message}", cause = e)
    }
}

private const val ERROR_EXCERPT = 200
