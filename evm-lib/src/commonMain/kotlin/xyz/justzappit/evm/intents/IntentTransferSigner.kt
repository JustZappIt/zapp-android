// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.intents.IntentPayload.VERIFYING_CONTRACT
import kotlin.io.encoding.Base64

/**
 * Signs a `generate-intent` payload only if it moves exactly the reviewed amount of the reviewed token from this
 * account to the quote's deposit address, and nothing else. The payload must re-encode to itself, so duplicate
 * keys or escapes can't show another parser a different transfer than the one checked.
 */
object IntentTransferSigner {
    const val STANDARD = Erc191IntentSigner.STANDARD

    /** How far ahead a payload deadline may be. */
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

    sealed interface VerifyResult {
        data class Verified(
            val deadlineMillis: Long,
        ) : VerifyResult

        data class Refused(
            val reason: Rejection,
        ) : VerifyResult
    }

    sealed interface SignResult {
        data class Signed(
            val payload: String,
            val signature: String,
        ) : SignResult {
            val standard: String get() = STANDARD
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
    ): SignResult =
        when (val result = verify(payload, IntentsAccount.accountId(key), expected, nowMillis, maxTtlMillis)) {
            is VerifyResult.Verified -> SignResult.Signed(payload, Erc191IntentSigner.sign(key, payload))
            is VerifyResult.Refused -> SignResult.Refused(result.reason)
        }

    /** The check [sign] makes, without signing, so a payload can be shown before the user approves it. */
    fun verify(
        payload: String,
        accountId: String,
        expected: ExpectedTransfer,
        nowMillis: Long,
        maxTtlMillis: Long = DEFAULT_MAX_TTL_MILLIS,
    ): VerifyResult {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
        val deadline = root?.string("deadline")?.let(IntentPayload::parseIsoMillis)
        return when {
            root == null -> refused(Rejection.NOT_A_JSON_OBJECT)
            json.encodeToString(JsonElement.serializer(), root) != payload -> refused(Rejection.NOT_CANONICAL)
            root.keys != PAYLOAD_KEYS -> refused(Rejection.UNEXPECTED_FIELDS)
            root.string("signer_id") != accountId -> refused(Rejection.WRONG_SIGNER)
            root.string("verifying_contract") != VERIFYING_CONTRACT -> refused(Rejection.WRONG_VERIFYING_CONTRACT)
            deadline == null -> refused(Rejection.UNREADABLE_DEADLINE)
            deadline <= nowMillis -> refused(Rejection.EXPIRED)
            deadline > nowMillis + maxTtlMillis -> refused(Rejection.DEADLINE_TOO_FAR)
            !root.hasNonce() -> refused(Rejection.UNREADABLE_NONCE)
            !root.hasOnlyTransfer(expected) -> refused(Rejection.UNEXPECTED_INTENTS)
            else -> VerifyResult.Verified(deadline)
        }
    }

    private fun refused(reason: Rejection) = VerifyResult.Refused(reason)

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
