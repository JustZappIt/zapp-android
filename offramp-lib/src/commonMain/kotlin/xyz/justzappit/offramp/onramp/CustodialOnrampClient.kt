// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

class OnrampException(
    val code: OnrampFailureCode,
    val httpStatus: Int,
    override val message: String,
) : Exception(message)

/**
 * Talks to the Zapp onramp service, which owns the reputation-bearing operator account and places
 * every BUY. Amounts cross the wire as 6-decimal micro strings so they round-trip [Usdc6] exactly.
 *
 * Every call but `/v1/config` is signed. Nonces are single-use, so each signed call fetches its own
 * immediately beforehand.
 */
@Suppress("TooManyFunctions")
class CustodialOnrampClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val signerProvider: OnrampSignerProvider,
    private val appId: String = OnrampRequestSigner.DEFAULT_APP_ID,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Device-signal fields split two ways, and the service distinguishes them: a field with no
    // default (doNotTrack) is always sent, as an explicit null, because omitting it 500s the
    // service; a field defaulted to null (seonSession) is omitted entirely, which is the honest
    // encoding of "this device has no such signal". encodeDefaults = false is what draws that line.
    private val requestJson = ONRAMP_REQUEST_JSON

    /**
     * Corridor configuration. [currency] is required to get the right one: the service serves every
     * corridor from this one endpoint and answers for its own default when asked for nothing, and
     * the caps are derived from that corridor's live buy price, so INR's are a different number
     * from BRL's rather than the same number in another symbol.
     *
     * Omit [currency] only when all that is wanted is the [OnrampConfigDto.nonce], which every
     * corridor's response carries.
     */
    suspend fun config(currency: CurrencyCode? = null): OnrampConfigDto =
        decode(
            OnrampConfigDto.serializer(),
            httpClient.get(url(PATH_CONFIG)) {
                currency?.let { parameter(QUERY_CURRENCY, it.code) }
            },
        )

    suspend fun quote(
        fiatAmount: Usdc6,
        currency: CurrencyCode,
    ): OnrampQuoteDto =
        signedPost(
            PATH_QUOTE,
            requestJson.encodeToString(
                QuoteRequestDto.serializer(),
                QuoteRequestDto(fiatAmount = fiatAmount.micros.toString(), currency = currency.code),
            ),
            OnrampQuoteDto.serializer(),
        )

    suspend fun createOrder(
        quoteId: String,
        recipient: Address,
        device: OnrampDeviceSignals,
    ): OnrampOrderDto =
        signedPost(
            PATH_ORDERS,
            requestJson.encodeToString(
                CreateOrderRequestDto.serializer(),
                CreateOrderRequestDto(
                    quoteId = quoteId,
                    recipientAddr = recipient.checksumHex,
                    device = device,
                ),
            ),
            OnrampOrderDto.serializer(),
        )

    suspend fun order(id: String): OnrampOrderDto = signedGet("$PATH_ORDERS/$id", OnrampOrderDto.serializer())

    /**
     * Asserts on-chain that fiat moved, which releases the merchant's USDC. Never call this on a
     * timer or a 5xx retry: a false assertion costs a merchant real money and burns the operator
     * reputation every user of this route shares.
     */
    suspend fun markPaid(id: String): OnrampOrderDto =
        signedPost("$PATH_ORDERS/$id/paid", "", OnrampOrderDto.serializer())

    suspend fun cancel(id: String): OnrampOrderDto =
        signedPost("$PATH_ORDERS/$id/cancel", "", OnrampOrderDto.serializer())

    private suspend fun <T> signedGet(
        path: String,
        serializer: DeserializationStrategy<T>,
    ): T =
        decode(
            serializer,
            withFreshNonce(METHOD_GET, path, "") { nonce, signature, address ->
                httpClient.get(url(path)) { authHeaders(nonce, signature, address) }
            }
        )

    private suspend fun <T> signedPost(
        path: String,
        body: String,
        serializer: DeserializationStrategy<T>,
    ): T =
        decode(
            serializer,
            withFreshNonce(METHOD_POST, path, body) { nonce, signature, address ->
                httpClient.post(url(path)) {
                    // Every POST here changes state on-chain: one places a real BUY, one asserts
                    // that fiat moved and releases a merchant's USDC. The shared offramp client
                    // installs HttpRequestRetry, which would re-send all of them on a 5xx, a 429
                    // or a socket timeout — and a socket timeout is exactly the case where the
                    // service already acted. The nonce retry in [withFreshNonce] is the one
                    // deliberate re-send, and it only fires on a refusal that proves inaction.
                    retry { noRetry() }
                    authHeaders(nonce, signature, address)
                    if (body.isNotEmpty()) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            }
        )

    /**
     * A nonce can expire or be spent between the fetch and the call it authorises, so a
     * `NONCE_INVALID` earns exactly one retry with a fresh one. Any other failure propagates.
     */
    private suspend fun withFreshNonce(
        method: String,
        path: String,
        body: String,
        send: suspend (nonce: String, signature: String, address: Address) -> HttpResponse,
    ): HttpResponse {
        // Callers arrive on viewModelScope's Dispatchers.Main.immediate and nothing in between
        // switches, so deriving the key and signing would run keccak, SHA-256 and a secp256k1
        // signature on the UI thread — once per signed call, and a nonce is single-use, so twice
        // per poll for as long as an order is live. Default rather than the IO used elsewhere in
        // this repo: the repeated cost here is CPU, and IO's pool is sized for blocking calls.
        val signer = withContext(Dispatchers.Default) { signerProvider.signer() }
        var attempt = 0
        while (true) {
            val nonce = config().nonce
            val signature = withContext(Dispatchers.Default) { signer.sign(nonce, method, path, body) }
            val response = send(nonce, signature, signer.address)
            if (response.status.isSuccess() || attempt >= 1) return response
            val text = response.bodyAsText()
            if (errorCodeOf(text) != OnrampFailureCode.NONCE_INVALID) {
                throw toException(response.status.value, text)
            }
            attempt++
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders(
        nonce: String,
        signature: String,
        address: Address,
    ) {
        header(HEADER_APP, appId)
        header(HEADER_ADDRESS, address.checksumHex)
        header(HEADER_NONCE, nonce)
        header(HEADER_SIGNATURE, signature)
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private suspend fun <T> decode(
        serializer: DeserializationStrategy<T>,
        response: HttpResponse,
    ): T {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw toException(response.status.value, text)
        return json.decodeFromString(serializer, text)
    }

    private fun toException(
        status: Int,
        body: String,
    ): OnrampException {
        val code = errorCodeOf(body)
        val message =
            runCatching {
                json.decodeFromString(JsonObject.serializer(), body)["message"]?.jsonPrimitive?.content
            }.getOrNull()
        return OnrampException(code, status, message ?: "onramp service returned $status")
    }

    private fun errorCodeOf(body: String): OnrampFailureCode =
        runCatching {
            OnrampFailureCode.fromWire(
                json.decodeFromString(JsonObject.serializer(), body)["code"]?.jsonPrimitive?.content,
            )
        }.getOrDefault(OnrampFailureCode.UNKNOWN)

    private companion object {
        const val PATH_CONFIG = "/v1/config"
        const val QUERY_CURRENCY = "currency"
        const val PATH_QUOTE = "/v1/quote"
        const val PATH_ORDERS = "/v1/orders"
        const val METHOD_GET = "GET"
        const val METHOD_POST = "POST"
        const val HEADER_APP = "x-p2p-app"
        const val HEADER_ADDRESS = "x-p2p-address"
        const val HEADER_NONCE = "x-p2p-nonce"
        const val HEADER_SIGNATURE = "x-p2p-signature"
    }
}

internal val ONRAMP_REQUEST_JSON =
    Json {
        encodeDefaults = false
    }

@Serializable
internal data class QuoteRequestDto(
    val fiatAmount: String,
    val currency: String,
)

@Serializable
internal data class CreateOrderRequestDto(
    val quoteId: String,
    val recipientAddr: String,
    val device: OnrampDeviceSignals,
)

@Serializable
data class OnrampConfigDto(
    val nonce: String,
    val enabled: Boolean = false,
    val currency: String? = null,
    val minFiat: String? = null,
    val maxFiat: String? = null,
    val perUserDailyFiat: String? = null,
    val chainId: Long? = null,
) {
    /**
     * A corridor this build has no [CurrencyCode] for is disabled, never quietly re-labelled. The
     * service serves corridors this app does not know (MEX at the time of writing), and defaulting
     * an unrecognised one to INR would render its caps and its amounts under a rupee sign.
     */
    fun toLimits(): OnrampLimits {
        val known = currency?.let(CurrencyCode::fromCodeOrNull) ?: return OnrampLimits.DISABLED
        return OnrampLimits(
            enabled = enabled,
            currency = known,
            minFiat = minFiat.toUsdc6OrNull() ?: Usdc6.ZERO,
            maxFiat = maxFiat.toUsdc6OrNull() ?: Usdc6.ZERO,
            perUserDailyFiat = perUserDailyFiat.toUsdc6OrNull() ?: Usdc6.ZERO,
        )
    }
}

@Serializable
data class OnrampQuoteDto(
    val quoteId: String,
    val currency: String? = null,
    val fiatAmount: String? = null,
    val grossUsdc: String? = null,
    val feeUsdc: String? = null,
    val netUsdc: String? = null,
    val buyPrice: String? = null,
    val expiresAt: Long = 0L,
) {
    fun toQuote(fallback: CurrencyCode): OnrampQuote =
        OnrampQuote(
            quoteId = quoteId,
            currency = currency?.let(CurrencyCode::fromCodeOrNull) ?: fallback,
            fiatAmount = fiatAmount.orThrow("fiatAmount"),
            grossUsdc = grossUsdc.orThrow("grossUsdc"),
            feeUsdc = feeUsdc.orThrow("feeUsdc"),
            netUsdc = netUsdc.orThrow("netUsdc"),
            buyPrice = buyPrice.orThrow("buyPrice"),
            expiresAtMillis = expiresAt,
        )
}

@Serializable
data class OnrampOrderDto(
    val id: String,
    val orderId: String? = null,
    val phase: String,
    val currency: String? = null,
    val fiatAmount: String? = null,
    val netUsdc: String? = null,
    @SerialName("recipientAddr") val recipientAddress: String? = null,
    val paymentInstruction: OnrampPaymentInstructionDto? = null,
    val placeTx: String? = null,
    val paidTx: String? = null,
    val expiresAt: Long? = null,
    val failureCode: String? = null,
    val createdAt: Long? = null,
) {
    fun toOrder(fallback: CurrencyCode): OnrampOrder =
        OnrampOrder(
            id = id,
            orderId = orderId,
            phase = OnrampPhase.fromWire(phase) ?: OnrampPhase.FAILED,
            // An order named in a currency this build cannot render is the service and the app
            // disagreeing about the corridor, which is never safe to paper over with the one the
            // user happened to pick: the amount to pay would be shown in the wrong money.
            currency = currency.toCurrencyOrThrow(fallback),
            fiatAmount = fiatAmount.toUsdc6OrNull(),
            netUsdc = netUsdc.toUsdc6OrNull(),
            recipientAddress = recipientAddress?.let(Address::parseOrNull),
            paymentInstruction = paymentInstruction?.toInstruction(),
            placeTx = placeTx,
            paidTx = paidTx,
            expiresAtMillis = expiresAt,
            failureCode = failureCode?.let { OnrampFailureCode.fromWire(it) },
            createdAtMillis = createdAt,
        )
}

@Serializable
data class OnrampPaymentInstructionDto(
    val kind: String,
    val address: String? = null,
    val intentUrl: String? = null,
    val amount: String? = null,
    val payload: String? = null,
    val fields: List<OnrampPaymentFieldDto>? = null,
) {
    fun toInstruction(): OnrampPaymentInstruction? =
        when (kind) {
            KIND_UPI -> {
                if (address != null && intentUrl != null) {
                    OnrampPaymentInstruction.Upi(address, intentUrl, amount.orEmpty())
                } else {
                    null
                }
            }

            KIND_QR -> {
                payload?.let(OnrampPaymentInstruction::Qr)
            }

            KIND_FIELDS -> {
                fields
                    ?.map { OnrampPaymentInstruction.Field(it.label, it.value) }
                    ?.let(OnrampPaymentInstruction::Fields)
            }

            KIND_PLAIN -> {
                address?.let(OnrampPaymentInstruction::Plain)
            }

            else -> {
                null
            }
        }

    private companion object {
        const val KIND_UPI = "upi"
        const val KIND_QR = "qr"
        const val KIND_FIELDS = "fields"
        const val KIND_PLAIN = "plain"
    }
}

@Serializable
data class OnrampPaymentFieldDto(
    val label: String,
    val value: String,
)

/** Absent means "the corridor the caller asked for"; present but unknown means the two disagree. */
internal fun String?.toCurrencyOrThrow(fallback: CurrencyCode): CurrencyCode =
    this?.takeIf { it.isNotBlank() }?.let { raw ->
        CurrencyCode.fromCodeOrNull(raw)
            ?: throw OnrampException(OnrampFailureCode.UPSTREAM_FAILED, 0, "unsupported currency from service")
    } ?: fallback

/**
 * Amounts cross the wire as 6-decimal micro strings. Anything else is the service and the app
 * disagreeing about units, which is never safe to paper over with a zero.
 */
internal fun String?.toUsdc6OrNull(): Usdc6? =
    this?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { Usdc6(BigInteger(raw)) }
            .getOrElse { throw OnrampException(OnrampFailureCode.UPSTREAM_FAILED, 0, "malformed amount from service") }
    }

private fun String?.orThrow(field: String): Usdc6 =
    toUsdc6OrNull() ?: throw OnrampException(OnrampFailureCode.UPSTREAM_FAILED, 0, "service quote is missing $field")
