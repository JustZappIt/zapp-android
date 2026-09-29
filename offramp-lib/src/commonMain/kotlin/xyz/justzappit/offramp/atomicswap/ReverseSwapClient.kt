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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Suppress("TooManyFunctions")
class ReverseSwapClient(
    private val http: HttpClient,
    private val deployment: ReverseDeployment
) : ReverseSwapApi {
    override suspend fun info(): ReverseMakerInfo = get(deployment.makerUrl, "/v1/info")

    override suspend fun quote(units: Int, user: String, refundNote: String): ReverseQuote =
        post(deployment.makerUrl, "/v1/reverse/quote", Request(units, user, refundNote))

    override suspend fun accept(quoteId: String, acceptance: ReverseAcceptance): String =
        post<ReverseAcceptance, Accepted>(deployment.makerUrl, "/v1/reverse/quote/$quoteId/accept", acceptance).swapId

    override suspend fun terms(): ReverseRelayerTerms = get(deployment.relayerUrl, "/v1/terms")

    override suspend fun ready(authorization: ReverseAuthorization) = relay("ready", authorization)

    override suspend fun lockRefund(authorization: ReverseAuthorization) = relay("lock-refund", authorization)

    override suspend fun refund(request: ReverseRefund) = relay("refund", request)

    override suspend fun payout(request: ReversePayout) = relay("refund-payout", request)

    override suspend fun rescue(request: ReversePayout) = relay("rescue", request)

    private suspend inline fun <reified T> relay(path: String, request: T) {
        val sent = post<T, Sent>(deployment.relayerUrl, "/v1/reverse/$path", request)
        sent.transactions.forEach { fixedHex(it, SWAP_WORD_BYTES) }
    }

    private suspend inline fun <reified T> get(base: String, path: String): T =
        decode(http.get(base.trimEnd('/') + path))

    private suspend inline fun <reified T, reified R> post(base: String, path: String, request: T): R =
        decode(
            http.post(base.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
        )

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = json.decodeFromString<ReverseServiceError>(body)
            throw ReverseServiceException(error.code, response.status.value)
        }
        return json.decodeFromString(body)
    }

    @Serializable
    private data class Request(
        val units: Int,
        val user: String,
        val refundNote: String
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
enum class ReverseErrorCode {
    @SerialName("invalidRequest")
    INVALID_REQUEST,

    @SerialName("rejected")
    REJECTED,

    @SerialName("unknownQuote")
    UNKNOWN_QUOTE,

    @SerialName("unknownSwap")
    UNKNOWN_SWAP,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("watchtowerUnavailable")
    WATCHTOWER_UNAVAILABLE,

    @SerialName("internal")
    INTERNAL,

    @SerialName("notFound")
    NOT_FOUND,

    @SerialName("methodNotAllowed")
    METHOD_NOT_ALLOWED,
}

@Serializable
private data class ReverseServiceError(
    val code: ReverseErrorCode,
    val error: String
)

class ReverseServiceException(
    val code: ReverseErrorCode,
    val status: Int
) : Exception("$code ($status)")
