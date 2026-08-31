// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.abi.SolidityErrors
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.Gas
import xyz.justzappit.evm.types.Nonce
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex

class BaseRpcClient(
    private val httpClient: HttpClient,
    private val rpcUrl: String,
) {
    private val nextId = RpcIdSequence()
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    suspend fun ethChainId(): ChainId =
        ChainId(hexToBigInteger(rpcCall("eth_chainId", emptyJsonArray).jsonPrimitive.content).toLong())

    suspend fun ethGasPrice(): Wei =
        Wei(hexToBigInteger(rpcCall("eth_gasPrice", emptyJsonArray).jsonPrimitive.content))

    suspend fun ethMaxPriorityFeePerGas(): Wei =
        Wei(hexToBigInteger(rpcCall("eth_maxPriorityFeePerGas", emptyJsonArray).jsonPrimitive.content))

    suspend fun ethGetTransactionCount(address: Address, blockTag: String = "pending"): Nonce =
        Nonce(
            hexToBigInteger(
                rpcCall(
                    "eth_getTransactionCount",
                    buildJsonArray {
                        add(address.checksumHex)
                        add(blockTag)
                    },
                ).jsonPrimitive.content,
            ),
        )

    /**
     * [from] is only needed when the contract's answer depends on the caller — a `view` read does
     * not care, but simulating a write before paying for it does: the ReputationManager compares
     * the proof's context address against `msg.sender`, and a simulation with no sender proves
     * nothing about the account that will actually submit.
     */
    suspend fun ethCall(
        to: Address,
        data: ByteArray,
        blockTag: String = "latest",
        from: Address? = null,
    ): ByteArray =
        rpcCall(
            "eth_call",
            buildJsonArray {
                addJsonObject {
                    from?.let { put("from", it.checksumHex) }
                    put("to", to.checksumHex)
                    put("data", "0x" + data.toHex())
                }
                add(blockTag)
            },
        ).jsonPrimitive.content.removePrefix("0x").let { if (it.isEmpty()) byteArrayOf() else it.hexToBytes() }

    suspend fun ethEstimateGas(
        from: Address,
        to: Address,
        value: Wei = Wei.ZERO,
        data: ByteArray = byteArrayOf(),
    ): Gas =
        Gas(
            hexToBigInteger(
                rpcCall(
                    "eth_estimateGas",
                    buildJsonArray {
                        addJsonObject {
                            put("from", from.checksumHex)
                            put("to", to.checksumHex)
                            put("value", "0x" + value.value.toString(HEX_BASE))
                            put("data", "0x" + data.toHex())
                        }
                    },
                ).jsonPrimitive.content,
            ),
        )

    suspend fun ethSendRawTransaction(rawTxHex: String): TxHash =
        TxHash.fromHex(
            rpcCall(
                "eth_sendRawTransaction",
                buildJsonArray { add(if (rawTxHex.startsWith("0x")) rawTxHex else "0x$rawTxHex") },
            ).jsonPrimitive.content,
        )

    suspend fun ethGetCode(address: Address, blockTag: String = "latest"): ByteArray =
        rpcCall(
            "eth_getCode",
            buildJsonArray {
                add(address.checksumHex)
                add(blockTag)
            },
        ).jsonPrimitive.content.removePrefix("0x").let { if (it.isEmpty()) byteArrayOf() else it.hexToBytes() }

    suspend fun ethGetTransactionReceipt(txHash: TxHash): TransactionReceipt? {
        val result = rpcCall("eth_getTransactionReceipt", buildJsonArray { add(txHash.hex) })
        if (result is JsonPrimitive && result.content == "null") return null
        if (result.toString() == "null") return null
        return json.decodeFromJsonElement(TransactionReceipt.serializer(), result)
    }

    suspend fun ethGetBlockByNumber(blockTag: String = "latest"): BlockHeader {
        val result =
            rpcCall(
                "eth_getBlockByNumber",
                buildJsonArray {
                    add(blockTag)
                    add(false)
                },
            )
        return json.decodeFromJsonElement(BlockHeader.serializer(), result)
    }

    private suspend fun rpcCall(method: String, params: JsonArray): JsonElement {
        val payload =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextId.next())
                put("method", method)
                put("params", params)
            }
        val response =
            try {
                httpClient.post(rpcUrl) {
                    contentType(ContentType.Application.Json)
                    if (method == METHOD_SEND_RAW_TRANSACTION) attributes.put(NoRpcRetry, Unit)
                    setBody(payload)
                }
            } catch (e: IOException) {
                // Timeouts and socket failures (post ktor-retry exhaustion) surface as IOException.
                throw RpcException.TransportError(method, e)
            }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw RpcException.RateLimited(method, response.retryAfterMillis())
        }
        val body: JsonObject = response.body()

        body["error"]?.let { errEl -> throw classifyError(method, errEl.jsonObject, body.toString()) }
        return body["result"] ?: error("RPC response missing 'result': $body")
    }

    private fun HttpResponse.retryAfterMillis(): Long? =
        headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(MILLIS_PER_SECOND)

    private fun classifyError(method: String, error: JsonObject, raw: String): RpcException {
        val code = error["code"]?.jsonPrimitive?.content?.toIntOrNull()
        val message = error["message"]?.jsonPrimitive?.content
        val dataHex = (error["data"] as? JsonPrimitive)?.contentOrNullIfStringNull()

        // Execution reverted: geth uses code=3; some vendors use -32000 + "execution reverted" in
        // the message. If we see either, parse selector/Error(string) from the data field.
        val looksLikeRevert =
            code == EXECUTION_REVERTED_CODE ||
                (message != null && message.contains("execution reverted", ignoreCase = true))
        if (looksLikeRevert) {
            val revertBytes =
                dataHex
                    ?.takeIf { it.length >= MIN_HEX_LEN_FOR_BYTES }
                    ?.runCatching { hexToBytes() }
                    ?.getOrNull()
            return RpcException.ExecutionReverted(
                method = method,
                selector = revertBytes?.let(Selector4::fromBytesPrefix),
                data = revertBytes ?: EMPTY_REVERT_DATA,
                solidityErrorString = revertBytes?.let(SolidityErrors::decodeErrorString),
                rawMessage = message.orEmpty(),
            )
        }

        return when (code) {
            METHOD_NOT_FOUND_CODE -> RpcException.MethodNotFound(method)
            INVALID_PARAMS_CODE -> RpcException.InvalidParams(method, message.orEmpty())
            else -> RpcException.Unknown(method = method, code = code, raw = raw, errorMessage = message)
        }
    }

    private fun JsonPrimitive.contentOrNullIfStringNull(): String? =
        if (isString) content.takeUnless { it.equals("null", ignoreCase = true) } else content

    private val emptyJsonArray = JsonArray(emptyList())

    companion object {
        private const val METHOD_SEND_RAW_TRANSACTION = "eth_sendRawTransaction"
        private const val EXECUTION_REVERTED_CODE = 3
        private const val METHOD_NOT_FOUND_CODE = -32_601
        private const val INVALID_PARAMS_CODE = -32_602
        private const val MIN_HEX_LEN_FOR_BYTES = 2 // "0x" or single byte
        private const val HEX_BASE = 16
        private const val MILLIS_PER_SECOND = 1_000L
        private val EMPTY_REVERT_DATA = ByteArray(0)
    }
}
