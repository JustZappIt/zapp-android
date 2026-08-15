// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.signer.UserOperationV06
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.toHex

/**
 * ERC-4337 bundler + ERC-7677 verifying-paymaster JSON-RPC, hosted by Pimlico. Self-custodial: the
 * caller signs each [UserOperationV06] locally; this client never holds a key. Distinct from
 * [BaseRpcClient] (the node RPC) — it targets `https://api.pimlico.io/v2/<chainId>/rpc?apikey=…`
 * and speaks UserOperation methods rather than `eth_sendRawTransaction`. The API key is in the URL
 * query string per Pimlico's spec, so callers should not log the bundler URL verbatim.
 */
class BundlerClient(
    private val httpClient: HttpClient,
    private val bundlerUrl: String,
    private val entryPoint: Address,
    private val chainId: ChainId,
    /**
     * Optional Pimlico ERC-7677 sponsorship-policy identifier. When non-blank, every
     * `pm_sponsorUserOperation` request is scoped to this specific policy (created in the Pimlico
     * dashboard) — so a stolen `PIMLICO_API_KEY` extracted from the APK can only sponsor calls
     * matching the policy's (target contract, function selector, sender, amount) constraints.
     * Without a policy id, Pimlico falls back to the project's default sponsorship rules — fine for
     * testnet/dev, risky for mainnet where the blast radius is your project budget.
     */
    private val sponsorshipPolicyId: String? = null,
) {
    private val nextId = RpcIdSequence()
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    /**
     * Pimlico returns three priority tiers (`slow`/`standard`/`fast`). We pick `standard` as the
     * default — `fast` overpays for normal use, `slow` risks bundler rejection if mempool conditions
     * shift between estimation and submission. Swap tiers here if a single chain needs different
     * latency/cost trade-offs.
     */
    suspend fun getUserOperationGasPrice(): UserOpGasPrice {
        val result = rpcCall("pimlico_getUserOperationGasPrice", JsonArray(emptyList())) as JsonObject
        val standard = result["standard"] ?: error("pimlico_getUserOperationGasPrice missing 'standard' tier: $result")
        return json.decodeFromJsonElement(UserOpGasPrice.serializer(), standard)
    }

    suspend fun estimateUserOperationGas(op: UserOperationV06): UserOpGasEstimate =
        retryOnNonceLag {
            json.decodeFromJsonElement(
                UserOpGasEstimate.serializer(),
                rpcCall(
                    "eth_estimateUserOperationGas",
                    buildJsonArray {
                        add(userOpJson(op))
                        add(entryPoint.checksumHex)
                    },
                ),
            )
        }

    /**
     * Placeholder paymaster data (same shape/length as the real thing) so gas estimation accounts
     * for the paymaster's validation. ERC-7677 step before [estimateUserOperationGas].
     *
     * Pimlico's stub method is non-standard: 3rd param is the chain ID hex string, not an ERC-7677
     * context object (that's what [sponsorUserOperation] takes). See Pimlico paymaster docs.
     */
    suspend fun getPaymasterStubData(op: UserOperationV06): PaymasterResult =
        json.decodeFromJsonElement(
            PaymasterResult.serializer(),
            rpcCall(
                "pm_getPaymasterStubData",
                buildJsonArray {
                    add(userOpJson(op))
                    add(entryPoint.checksumHex)
                    add(chainId.hex)
                },
            ),
        )

    /** Asks the paymaster to sponsor [op]; the returned [PaymasterResult.paymasterAndData] goes back into the op. */
    suspend fun sponsorUserOperation(op: UserOperationV06): PaymasterResult =
        retryOnNonceLag {
            json.decodeFromJsonElement(
                PaymasterResult.serializer(),
                rpcCall(
                    "pm_sponsorUserOperation",
                    buildJsonArray {
                        add(userOpJson(op))
                        add(entryPoint.checksumHex)
                        // ERC-7677 context object. When sponsorshipPolicyId is set, scope this
                        // sponsorship to a specific Pimlico-dashboard policy — that's the lever
                        // for blast-radius containment if the in-APK API key is extracted.
                        add(
                            buildJsonObject {
                                if (!sponsorshipPolicyId.isNullOrBlank()) {
                                    put("sponsorshipPolicyId", sponsorshipPolicyId)
                                }
                            },
                        )
                    },
                ),
            )
        }

    suspend fun sendUserOperation(op: UserOperationV06): TxHash =
        retryOnNonceLag {
            TxHash.fromHex(
                rpcCall(
                    "eth_sendUserOperation",
                    buildJsonArray {
                        add(userOpJson(op))
                        add(entryPoint.checksumHex)
                    },
                ).jsonPrimitive.content,
            )
        }

    /**
     * The mined transaction receipt for a userOp, or null while it is still pending.
     *
     * The bundle transaction's own `status` is 0x1 whenever `handleOps` itself succeeded — EntryPoint
     * catches an inner revert and emits `UserOperationRevertReason` rather than reverting the bundle.
     * Only the response's top-level `success` says whether *this* userOp executed, so it is folded
     * into the returned receipt's status; callers read [TransactionReceipt.success] and mean the
     * operation, not the bundle.
     */
    suspend fun getUserOperationReceipt(userOpHash: TxHash): TransactionReceipt? {
        val result = rpcCall("eth_getUserOperationReceipt", buildJsonArray { add(userOpHash.hex) })
        // Pending is represented only by JSON null. Any other shape is a malformed mined verdict,
        // not evidence that the operation is still pending.
        if (result is JsonNull) return null
        val response =
            result as? JsonObject
                ?: error("Bundler returned a malformed UserOperation receipt")
        val receipt = response["receipt"] ?: error("Bundler UserOperation result is missing its receipt")
        val decoded = json.decodeFromJsonElement(TransactionReceipt.serializer(), receipt)
        val operationSucceeded =
            response["success"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull
                ?: error("Bundler UserOperation receipt is missing a boolean success verdict")
        return if (operationSucceeded) decoded else decoded.copy(status = FAILED_STATUS)
    }

    private fun userOpJson(op: UserOperationV06): JsonObject =
        buildJsonObject {
            put("sender", op.sender.checksumHex)
            putHex("nonce", op.nonce)
            putBytes("initCode", op.initCode)
            putBytes("callData", op.callData)
            putHex("callGasLimit", op.callGasLimit)
            putHex("verificationGasLimit", op.verificationGasLimit)
            putHex("preVerificationGas", op.preVerificationGas)
            putHex("maxFeePerGas", op.maxFeePerGas)
            putHex("maxPriorityFeePerGas", op.maxPriorityFeePerGas)
            putBytes("paymasterAndData", op.paymasterAndData)
            putBytes("signature", op.signature)
        }

    private fun JsonObjectBuilder.putHex(key: String, value: BigInteger) =
        put(key, "0x" + value.toString(HEX_BASE))

    private fun JsonObjectBuilder.putBytes(key: String, value: ByteArray) =
        put(key, "0x" + value.toHex())

    /**
     * Pimlico's simulator runs `simulateValidation` against its own node RPC, which can lag the
     * chain by 1–3s after a UserOp lands. A back-to-back op at the next sequential nonce then
     * fails with AA25 even though our cursor matches the canonical on-chain `nonceSequenceNumber`.
     * Retry with backoff lets the simulator catch up; if the nonce is genuinely wrong, the final
     * attempt surfaces the error unchanged.
     */
    private suspend fun <T> retryOnNonceLag(block: suspend () -> T): T {
        var lastError: RpcException.Unknown? = null
        repeat(NONCE_LAG_MAX_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (e: RpcException.Unknown) {
                if (e.errorMessage?.contains("AA25") != true) throw e
                lastError = e
                if (attempt < NONCE_LAG_MAX_ATTEMPTS - 1) {
                    delay(NONCE_LAG_BASE_BACKOFF_MS shl attempt)
                }
            }
        }
        throw lastError!!
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
                httpClient.post(bundlerUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            } catch (e: IOException) {
                throw RpcException.TransportError(method, e)
            }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw RpcException.RateLimited(method, retryAfterMillis = null)
        }
        // Parse from text rather than ContentNegotiation: bundlers commonly serve over HTTP/2 and
        // ktor/OkHttp doesn't always negotiate those responses into a JsonObject, failing with
        // "expected JsonObject but was SourceByteReadChannel". Reading the text is content-type-
        // agnostic and also lets us surface the bundler's own error bodies.
        val text = response.bodyAsText()
        val element = json.parseToJsonElement(text)
        // JSON-RPC replies are envelopes ({jsonrpc,id,result|error}), but the bundler sometimes
        // returns a bare result value (e.g. eth_sendUserOperation's userOpHash). A non-object reply
        // can only be a result — errors are always objects — so pass it straight through.
        val body = element as? JsonObject ?: return element
        body["error"]?.let { errEl ->
            val err = errEl.jsonObject
            throw RpcException.Unknown(
                method = method,
                code = err["code"]?.jsonPrimitive?.content?.toIntOrNull(),
                raw = body.toString(),
                errorMessage = err["message"]?.jsonPrimitive?.content,
            )
        }
        return body["result"] ?: error("Bundler response missing 'result': $body")
    }

    companion object {
        private const val HEX_BASE = 16
        private const val NONCE_LAG_MAX_ATTEMPTS = 3
        private const val NONCE_LAG_BASE_BACKOFF_MS = 1_500L
        private const val FAILED_STATUS = "0x0"

        /**
         * Pimlico's bundler + verifying-paymaster URL for [chainId]. The API key is appended as a
         * query parameter per Pimlico's spec (no header auth). Pimlico accepts both the numeric
         * chain ID and a slug like "base" / "base-sepolia"; we use the numeric form so all chains
         * share one URL shape. Fails closed if [apiKey] is blank — surfaces at DI time rather than
         * the first network round-trip.
         */
        fun urlFor(chainId: ChainId, apiKey: String): String {
            require(apiKey.isNotBlank()) {
                "PIMLICO_API_KEY must be set in local.properties to use the bundler/paymaster"
            }
            return "https://api.pimlico.io/v2/${chainId.value}/rpc?apikey=$apiKey"
        }
    }
}
