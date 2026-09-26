// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds

/** The page's side is `railgun-lib/web/src/index.js`. */
internal object RailgunProtocol {
    const val INIT = "zapp-railgun-init"

    sealed interface Message {
        data class Result(
            val id: Long,
            val result: JsonElement
        ) : Message

        data class Failure(
            val id: Long,
            val error: String
        ) : Message

        data object Ready : Message

        data class Event(
            val event: RailgunEvent
        ) : Message
    }

    fun request(
        id: Long,
        method: String,
        params: JsonObject
    ): String =
        buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    /** Null for anything this build doesn't understand, which the host drops. */
    fun decode(data: String): Message? =
        runCatching { decodeObject(Json.parseToJsonElement(data).jsonObject) }.getOrNull()

    private fun decodeObject(message: JsonObject): Message? {
        val event = message["event"]?.jsonPrimitive?.content
        val id = message["id"]?.jsonPrimitive?.longOrNull
        val error = message["error"]
        return when {
            event != null -> decodeEvent(event, message["data"])
            id == null -> null
            error != null -> Message.Failure(id, error.jsonPrimitive.content)
            else -> Message.Result(id, message["result"] ?: JsonNull)
        }
    }

    private fun decodeEvent(
        event: String,
        data: JsonElement?
    ): Message? =
        when (event) {
            "ready" -> Message.Ready
            "log" -> data?.jsonPrimitive?.content?.let { Message.Event(RailgunEvent.Log(it)) }
            "scan" -> data?.jsonObject?.let(::decodeScan)?.let { Message.Event(it) }
            "proof" -> data?.jsonObject?.let(::decodeProof)?.let { Message.Event(it) }
            else -> null
        }

    private fun decodeScan(data: JsonObject): RailgunEvent.Scan? {
        val tree = RailgunMerkletree.entries.firstOrNull { it.wireName == data["tree"]?.jsonPrimitive?.content }
        val status = RailgunScanStatus.entries.firstOrNull { it.wireName == data["status"]?.jsonPrimitive?.content }
        return if (tree != null && status != null) {
            RailgunEvent.Scan(tree, status, data["progress"]?.jsonPrimitive?.floatOrNull ?: 0f)
        } else {
            null
        }
    }

    private fun decodeProof(data: JsonObject) =
        RailgunEvent.Proof(
            progress = data["progress"]?.jsonPrimitive?.floatOrNull ?: 0f,
            status = data["status"]?.jsonPrimitive?.content.orEmpty(),
        )

    fun decodeGasAccount(result: JsonElement): RailgunGasAccount {
        val account = result.jsonObject
        return RailgunGasAccount(
            address = account.getValue("address").jsonPrimitive.content,
            balance = BigInteger(account.getValue("balance").jsonPrimitive.content),
        )
    }

    fun decodeSent(result: JsonElement): RailgunSent {
        val sent = result.jsonObject
        return RailgunSent(
            txHash = sent.getValue("txHash").jsonPrimitive.content,
            proofDuration = sent["proofMs"]?.jsonPrimitive?.longOrNull?.milliseconds,
        )
    }

    fun decodeBalances(result: JsonElement): RailgunBalances {
        val buckets = RailgunBalanceBucket.entries.associateBy { it.wireName }
        return RailgunBalances(
            result.jsonObject.entries
                .mapNotNull { (name, tokens) ->
                    val bucket = buckets[name] ?: return@mapNotNull null
                    bucket to
                        tokens.jsonArray.map {
                            val token = it.jsonObject
                            RailgunTokenAmount(
                                token = token.getValue("token").jsonPrimitive.content,
                                amount = BigInteger(token.getValue("amount").jsonPrimitive.content),
                            )
                        }
                }.toMap()
        )
    }
}
