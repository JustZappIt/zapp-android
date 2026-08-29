// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.padLeftToWord
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.io.encoding.Base64

/**
 * Endpoint and symmetric key for the screening service. The key is AES-256 and the same one both
 * sides hold — p2p's design ships it in the client, which is why the payload it protects carries
 * device signals rather than anything that would let a holder of the key act as the user.
 */
data class OnrampScreeningConfig(
    val apiUrl: String,
    val encryptionKeyHex: String,
) {
    val isConfigured: Boolean
        get() = apiUrl.isNotBlank() && encryptionKeyHex.isNotBlank()
}

/**
 * Who the record is about, and who signs for them.
 *
 * A smart account cannot produce an EIP-191 signature, so the request carries both addresses: the
 * EOA that actually signs, and the smart account that is the tracked subject and the address the
 * chain will show as the order's placer. Both are inside the signed message, which is how the EOA
 * proves it speaks for the smart account.
 */
data class OnrampScreeningSigner(
    val signingKey: EvmKey,
    val subject: Address,
)

data class OnrampScreeningOrder(
    val cryptoAmount: Usdc6,
    val fiatAmount: Usdc6,
    val currency: CurrencyCode,
    val recipientAddress: Address,
    val fee: Usdc6,
    val amountAfterFee: Usdc6,
    val paymentMethod: String?,
    val estimatedProcessingTimeSeconds: Long?,
)

sealed interface OnrampScreeningOutcome {
    /** Cleared. [activityLogId] must be linked to the order id once placement lands. */
    data class Approved(
        val activityLogId: JsonElement
    ) : OnrampScreeningOutcome

    /** The only outcome that stops a placement. Show [message] as the service worded it. */
    data class Rejected(
        val message: String
    ) : OnrampScreeningOutcome

    /**
     * The service could not be reached or answered badly. Fail-open: the order still places, and
     * there is no `activity_log_id`, so the link call is skipped.
     */
    data object Unavailable : OnrampScreeningOutcome
}

/**
 * Files the device-screening record that merchants gate on.
 *
 * This is not optional and not a nicety: an order with no screening record that matches its placer
 * routes and prices normally and is then simply never accepted — about 1 in 20 fill, against 91%
 * network-wide. On the operator route the service filed this itself, signed as the operator, which
 * is what made the record match the address the chain showed as the placer. On the direct route
 * the placer is the user's own smart account, so the app has to file it and the user has to sign
 * it, or it matches nothing.
 */
class OnrampScreeningClient(
    private val httpClient: HttpClient,
    private val config: OnrampScreeningConfig,
    private val deviceSignals: OnrampDeviceSignalsProvider,
    private val screeningSession: OnrampScreeningSessionProvider = OnrampScreeningSessionProvider.ABSENT,
    private val nowMillis: () -> Long,
) {
    @Suppress("ReturnCount")
    suspend fun screenBuyOrder(
        signer: OnrampScreeningSigner,
        order: OnrampScreeningOrder,
        country: String?,
    ): OnrampScreeningOutcome {
        require(config.isConfigured) { "screening is not configured" }
        // ☠ Two timestamps, two units, one request: the header is SECONDS, the body and the AAD
        // are MILLISECONDS, and the AAD must reuse the exact millisecond value sent in the body or
        // the payload fails to decrypt server-side with nothing pointing at the cause.
        val bodyMillis = nowMillis()
        val userAddress = signer.subject.lowercaseHex
        val payload = payloadJson(order, country)
        val encrypted =
            encrypt(
                plaintext = payload,
                aad = "$SCREENING_TYPE|$userAddress|$bodyMillis",
            )

        val response =
            httpClient.post("${config.apiUrl.trimEnd('/')}$PATH_ACTIVITY_LOGS") {
                contentType(ContentType.Application.Json)
                signedHeaders(signer, ACTION_ACTIVITY_LOG).forEach { (name, value) -> header(name, value) }
                setBody(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("type", SCREENING_TYPE)
                            put("user_address", userAddress)
                            put("timestamp", bodyMillis)
                            put("encrypted_payload", encrypted)
                        },
                    ),
                )
            }
        if (!response.status.isSuccess()) return OnrampScreeningOutcome.Unavailable

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val approved = body["approved"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        if (!approved) {
            return OnrampScreeningOutcome.Rejected(body["message"]?.jsonPrimitive?.content.orEmpty())
        }
        val logId = body["activity_log_id"] ?: return OnrampScreeningOutcome.Unavailable
        return OnrampScreeningOutcome.Approved(logId)
    }

    /**
     * Ties the screening record to the order id. This is the call that makes the record findable
     * from the order, so a merchant looking at the order can see it was screened.
     *
     * Fire-and-forget: a failure is logged and never surfaced, and the order stands either way.
     */
    suspend fun linkOrder(
        signer: OnrampScreeningSigner,
        activityLogId: JsonElement,
        orderId: BigInteger,
    ) {
        require(config.isConfigured) { "screening is not configured" }
        httpClient.patch("${config.apiUrl.trimEnd('/')}$PATH_LINK_ORDER") {
            contentType(ContentType.Application.Json)
            signedHeaders(signer, ACTION_LINK_ORDER).forEach { (name, value) -> header(name, value) }
            setBody(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("activity_log_id", activityLogId)
                        put("order_id", orderId.toString())
                        put("user_address", signer.subject.lowercaseHex)
                    },
                ),
            )
        }
    }

    /**
     * `{action}:{signingAddress}:{subjectAddress}:{timestamp}` over EIP-191, with the timestamp in
     * **seconds** — the variable-length form [OnrampRequestSigner] uses, not the fixed-length
     * `:\n32` form the Reclaim init and UserOp hashes use. The two recover different addresses.
     */
    internal fun signedHeaders(signer: OnrampScreeningSigner, action: String): Map<String, String> {
        val signingAddress = signer.signingKey.address.lowercaseHex
        val subjectAddress = signer.subject.lowercaseHex
        val timestampSeconds = (nowMillis() / MILLIS_PER_SECOND).toString()
        val message = "$action:$signingAddress:$subjectAddress:$timestampSeconds"
        return mapOf(
            "X-Signer-Address" to signingAddress,
            "X-Timestamp" to timestampSeconds,
            "X-Signature" to personalSign(signer.signingKey, message),
        )
    }

    internal suspend fun payloadJson(order: OnrampScreeningOrder, country: String?): String {
        val body =
            buildJsonObject {
                putJsonObject("user_details") {
                    put("currency", order.currency.code)
                    put("country", country)
                    put("language", null as String?)
                    // Zapp has no accounts: nothing was logged into, so nothing is claimed here.
                    put("login_method", JsonNull)
                    put("login_email", JsonNull)
                    put("login_phone", JsonNull)
                }
                putJsonObject("transaction_details") {
                    put("crypto_amount", order.cryptoAmount.asJsonNumber())
                    put("fiat_amount", order.fiatAmount.asJsonNumber())
                    put("currency", order.currency.code)
                    put("recipient_address", order.recipientAddress.lowercaseHex)
                    put("fee", order.fee.asJsonNumber())
                    put("amount_after_fee", order.amountAfterFee.asJsonNumber())
                    put("payment_method", order.paymentMethod)
                    put("estimated_processing_time", order.estimatedProcessingTimeSeconds?.toString())
                    put("order_timestamp", nowMillis())
                    put("order_source", ORDER_SOURCE)
                }
                put("device_details", deviceJson())
            }
        return Json.encodeToString(JsonObject.serializer(), body)
    }

    /**
     * AES-256-GCM, base64 of `iv ‖ ciphertext ‖ tag` — the layout the service reads, and the one
     * an IV-prefixing cipher produces for a 12-byte GCM nonce and a 16-byte tag. The IV comes from
     * the provider's CSPRNG per call rather than being passed in: supplying one is a delicate API
     * precisely because reusing a GCM nonce under a fixed key is catastrophic, and there is
     * nothing here that needs a chosen IV. [OnrampScreeningEncryptionTest] pins the layout.
     *
     * The AAD binds the record's type, its subject and its millisecond timestamp, so a payload
     * cannot be replayed under a different header.
     */
    internal fun encrypt(plaintext: String, aad: String): String {
        val key =
            CryptographyProvider.Default
                .get(AES.GCM)
                .keyDecoder()
                .decodeFromByteArrayBlocking(AES.Key.Format.RAW, config.encryptionKeyHex.decodeKeyHex())
        return Base64.encode(
            key.cipher().encryptBlocking(plaintext.encodeToByteArray(), aad.encodeToByteArray()),
        )
    }

    private suspend fun deviceJson(): JsonObject {
        val signals = deviceSignals.collect().copy(seonSession = screeningSession.session())
        return SCREENING_JSON.encodeToJsonElement(OnrampDeviceSignals.serializer(), signals).jsonObject
    }

    /**
     * Amounts cross the wire as JSON numbers in whole units, which is what the service's schema
     * declares. Emitted as an unquoted literal so a six-decimal amount survives exactly rather
     * than round-tripping through a double.
     */
    private fun Usdc6.asJsonNumber(): JsonElement =
        Json.parseToJsonElement(toDisplayString(stripTrailingZeros = true))

    private fun personalSign(key: EvmKey, message: String): String {
        val bytes = message.encodeToByteArray()
        val prefixed = byteArrayOf(EIP191_BYTE) + "$EIP191_HEADER${bytes.size}".encodeToByteArray() + bytes
        val signature = key.signRecoverable(keccak256(prefixed))
        return HEX_PREFIX +
            (
                signature.r.toByteArray().padLeftToWord() +
                    signature.s.toByteArray().padLeftToWord() +
                    byteArrayOf((signature.yParity + V_OFFSET).toByte())
            ).toHex()
    }

    private fun String.decodeKeyHex(): ByteArray {
        val raw = removePrefix("0x")
        require(raw.length == AES_256_KEY_HEX_LEN) {
            "screening key must be a 32-byte hex AES-256 key"
        }
        return ByteArray(raw.length / 2) { i ->
            raw.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte()
        }
    }

    private companion object {
        const val SCREENING_TYPE = "buy_order"
        const val ORDER_SOURCE = "zapp-android"
        const val ACTION_ACTIVITY_LOG = "activity-log"
        const val ACTION_LINK_ORDER = "link-order"
        const val PATH_ACTIVITY_LOGS = "/activity-logs"
        const val PATH_LINK_ORDER = "/activity-logs/link-order"
        const val MILLIS_PER_SECOND = 1_000L
        const val AES_256_KEY_HEX_LEN = 64
        const val HEX_RADIX = 16
        const val V_OFFSET = 27
        const val HEX_PREFIX = "0x"
        const val EIP191_HEADER = "Ethereum Signed Message:\n"
        const val EIP191_BYTE: Byte = 0x19

        /**
         * `seonSession` is sent explicitly as null rather than omitted. Zapp ships without SEON,
         * and saying so is more useful to the service than a field that simply is not there.
         */
        val SCREENING_JSON =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }
    }
}
