// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.justzappit.evm.hd.EvmKey
import kotlin.io.encoding.Base64

/**
 * Signs the `erc191` payload `POST /v0/generate-intent` returns for a sell, but only after checking that
 * it does exactly what the user reviewed: move [ExpectedTransfer.amount] of [ExpectedTransfer.tokenId] from
 * this account to the quote's deposit address, and nothing else. Anything unexpected is refused, not
 * signed, including shapes that might be harmless: widening this check is a deliberate code change. The
 * payload must also be compact JSON that re-encodes to exactly itself (as `JSON.stringify` output does), so
 * duplicate keys and escapes can't make another parser read a different transfer than the one checked.
 *
 * The expected shape (one `transfer` intent) follows the verifier contract's `Transfer` intent. It has not
 * yet been observed from `generate-intent` for a confidential deposit; the founder's pre-flight (runbook
 * step C0) captures a real payload, and this check is adjusted to it if it differs.
 */
object IntentTransferSigner {
    /** How far ahead a payload deadline may be. Bounds how long a signed, unsubmitted intent stays usable. */
    const val DEFAULT_MAX_TTL_MILLIS = 3_600_000L
    private const val INTENT_TRANSFER = "transfer"
    private val PAYLOAD_KEYS = setOf("signer_id", "verifying_contract", "deadline", "nonce", "intents")
    private val TRANSFER_KEYS = setOf("intent", "receiver_id", "tokens")
    private val json = Json { isLenient = false }

    data class ExpectedTransfer(
        val receiverId: String,
        val tokenId: String,
        /** Base units, as the quote's `amountIn` string. */
        val amount: String,
    )

    enum class Rejection {
        NOT_A_JSON_OBJECT,

        /** Not compact JSON that re-encodes to itself: duplicate keys, escapes, whitespace or odd literals. */
        NOT_CANONICAL,
        UNEXPECTED_FIELDS,
        WRONG_SIGNER,
        WRONG_VERIFYING_CONTRACT,
        UNREADABLE_DEADLINE,
        EXPIRED,
        DEADLINE_TOO_FAR,
        UNREADABLE_NONCE,
        UNEXPECTED_INTENTS,
    }

    sealed interface SignResult {
        /** Send as `signedData`: `{"standard": "erc191", "payload": [payload], "signature": [signature]}`. */
        data class Signed(
            val payload: String,
            val signature: String,
        ) : SignResult {
            val standard: String get() = Erc191IntentSigner.STANDARD
        }

        data class Refused(
            val reason: Rejection,
        ) : SignResult
    }

    fun sign(
        key: EvmKey,
        payload: String,
        expected: ExpectedTransfer,
        nowMillis: Long,
        maxTtlMillis: Long = DEFAULT_MAX_TTL_MILLIS,
    ): SignResult {
        val rejection = check(payload, IntentsAccount.accountId(key), expected, nowMillis, maxTtlMillis)
        return if (rejection == null) {
            SignResult.Signed(payload = payload, signature = Erc191IntentSigner.sign(key, payload))
        } else {
            SignResult.Refused(rejection)
        }
    }

    internal fun check(
        payload: String,
        accountId: String,
        expected: ExpectedTransfer,
        nowMillis: Long,
        maxTtlMillis: Long,
    ): Rejection? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
        val deadline = root?.string("deadline")?.let(IntentPayload::parseIsoMillis)
        // The parser keeps the last of two duplicate keys; a parser elsewhere that kept the first would act on
        // a transfer this check never saw. Requiring the payload to re-encode to exactly itself rules that
        // out, along with escapes and whitespace this check doesn't need to reason about.
        return when {
            root == null -> Rejection.NOT_A_JSON_OBJECT
            json.encodeToString(JsonElement.serializer(), root) != payload -> Rejection.NOT_CANONICAL
            root.keys != PAYLOAD_KEYS -> Rejection.UNEXPECTED_FIELDS
            root.string("signer_id") != accountId -> Rejection.WRONG_SIGNER
            root.string("verifying_contract") != IntentPayload.VERIFYING_CONTRACT -> Rejection.WRONG_VERIFYING_CONTRACT
            deadline == null -> Rejection.UNREADABLE_DEADLINE
            deadline <= nowMillis -> Rejection.EXPIRED
            deadline > nowMillis + maxTtlMillis -> Rejection.DEADLINE_TOO_FAR
            !root.hasNonce() -> Rejection.UNREADABLE_NONCE
            !root.hasOnlyTransfer(expected) -> Rejection.UNEXPECTED_INTENTS
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.hasNonce(): Boolean =
        string("nonce")
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
            ?.size == IntentNonce.SIZE

    private fun JsonObject.hasOnlyTransfer(expected: ExpectedTransfer): Boolean {
        val intent = (this["intents"] as? JsonArray)?.singleOrNull() as? JsonObject
        val tokens = intent?.get("tokens") as? JsonObject
        val amount = (tokens?.get(expected.tokenId) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return intent != null &&
            intent.keys == TRANSFER_KEYS &&
            intent.string("intent") == INTENT_TRANSFER &&
            intent.string("receiver_id") == expected.receiverId &&
            tokens?.size == 1 &&
            amount == expected.amount
    }
}
