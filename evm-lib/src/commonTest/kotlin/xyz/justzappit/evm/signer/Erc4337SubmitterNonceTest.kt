// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two operations on the same smart account must never sign against the same nonce. The bundler keeps
 * one of them and drops the other, and the caller of the dropped one waits out the receipt timeout
 * on an operation that will never appear.
 *
 * The mock yields on every request so the two coroutines interleave at each round trip, which is
 * exactly what a real withdrawal racing a matching toggle does.
 */
class Erc4337SubmitterNonceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sentNonces = mutableListOf<Long>()
    private var sendCount = 0

    /** Flipped by the reverted-receipt test; `null` keeps every operation pending. */
    private var receiptSucceeded: Boolean? = null

    /** Flipped by the failed-read test: the bundler answers the poll with an error rather than a verdict. */
    private var receiptReadFails = false
    private var failEstimate = false
    private var loseNextSendResponse = false
    private var rejectNextSend = false
    private var returnWrongHash = false
    private var preparedIdentity: TxHash? = null
    private var sendObservedPreparedIdentity = false

    private val engine =
        MockEngine { request ->
            yield()
            val body = json.parseToJsonElement(request.bodyText()).jsonObject
            val method = body["method"]?.jsonPrimitive?.content.orEmpty()
            var rejectThisSend = false
            if (method == METHOD_SEND) {
                sendObservedPreparedIdentity = preparedIdentity != null
                sentNonces += body.userOpNonce()
                sendCount++
                rejectThisSend = rejectNextSend
                rejectNextSend = false
                if (loseNextSendResponse) {
                    loseNextSendResponse = false
                    throw IOException("bundler accepted request but response was lost")
                }
            }
            respond(
                content =
                    if (method == "eth_estimateUserOperationGas" && failEstimate) {
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"estimation failed"}}"""
                    } else if (rejectThisSend) {
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"AA23 reverted"}}"""
                    } else if (method == METHOD_RECEIPT && receiptReadFails) {
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"internal error"}}"""
                    } else {
                        """{"jsonrpc":"2.0","id":1,"result":${resultFor(method, body)}}"""
                    },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    private val http = RpcHttpClient.create(engine = engine, config = RpcHttpClient.Config(maxRetries = 0))

    private val submitter =
        Erc4337Submitter(
            rpc = BaseRpcClient(httpClient = http, rpcUrl = "http://mock/rpc"),
            bundler =
                BundlerClient(
                    httpClient = http,
                    bundlerUrl = "http://mock/bundler",
                    entryPoint = ENTRY_POINT,
                    chainId = ChainId.BASE_MAINNET,
                ),
            entryPoint = ENTRY_POINT,
            accountFactory = FACTORY,
            owner = EvmKeyDerivation.derive(MNEMONIC),
            smartAccount = SMART_ACCOUNT,
            chainId = ChainId.BASE_MAINNET,
        )

    @AfterTest
    fun shutdown() {
        http.close()
    }

    @Test
    fun `concurrent sends from one account take consecutive nonces`() =
        runTest {
            listOf(
                async { submitter.sendTransaction(to = TARGET, data = byteArrayOf(1)) },
                async { submitter.sendTransaction(to = TARGET, data = byteArrayOf(2)) },
            ).awaitAll()

            assertEquals(listOf(0L, 1L), sentNonces)
        }

    @Test
    fun `signed identity is exposed immediately before the bundler send`() =
        runTest {
            val returned =
                submitter.sendTransaction(to = TARGET, data = byteArrayOf(1)) { identity ->
                    preparedIdentity = identity.hash
                }

            assertTrue(sendObservedPreparedIdentity)
            assertEquals(assertNotNull(preparedIdentity), returned)
        }

    @Test
    fun `a durable marker failure prevents the bundler send`() =
        runTest {
            assertFails {
                submitter.sendTransaction(to = TARGET, data = byteArrayOf(1)) {
                    error("checkpoint write failed")
                }
            }

            assertEquals(0, sendCount)

            submitter.sendTransaction(to = TARGET, data = byteArrayOf(2))
            assertEquals(listOf(0L), sentNonces)
        }

    @Test
    fun `a lost send response still advances past the nonce owned by the durable marker`() =
        runTest {
            var marker: TxHash? = null
            loseNextSendResponse = true

            assertFails {
                submitter.sendTransaction(to = TARGET, data = byteArrayOf(1)) { marker = it.hash }
            }
            submitter.sendTransaction(to = TARGET, data = byteArrayOf(2))

            assertNotNull(marker)
            assertEquals(listOf(0L, 1L), sentNonces)
        }

    @Test
    fun `a definite bundler rejection releases its nonce for a later send`() =
        runTest {
            var marker: TxHash? = null
            rejectNextSend = true

            assertFails {
                submitter.sendTransaction(to = TARGET, data = byteArrayOf(1)) { marker = it.hash }
            }
            submitter.sendTransaction(to = TARGET, data = byteArrayOf(2))

            assertNotNull(marker)
            assertEquals(listOf(0L, 0L), sentNonces)
        }

    @Test
    fun `a restored durable nonce cannot be reused after process restart`() =
        runTest {
            submitter.restorePendingTransaction(RESTORED_HASH, BigInteger("4"))

            submitter.sendTransaction(to = TARGET, data = byteArrayOf(1))

            assertEquals(listOf(5L), sentNonces)
        }

    @Test
    fun `a legacy exact hash blocks sends until its receipt proves inclusion`() =
        runTest {
            submitter.restorePendingTransaction(RESTORED_HASH, nonce = null)

            assertFails { submitter.sendTransaction(to = TARGET, data = byteArrayOf(1)) }
            receiptSucceeded = true
            assertNotNull(submitter.receiptIfIncluded(RESTORED_HASH))
            submitter.sendTransaction(to = TARGET, data = byteArrayOf(2))

            assertEquals(listOf(0L), sentNonces)
        }

    @Test
    fun `the bundler response must name the locally signed operation`() =
        runTest {
            returnWrongHash = true

            assertFails {
                submitter.sendTransaction(to = TARGET, data = byteArrayOf(1))
            }
        }

    @Test
    fun `a preparation failure creates no durable marker and sends nothing`() =
        runTest {
            var callbackRan = false
            failEstimate = true

            assertFails {
                submitter.sendTransaction(to = TARGET, data = byteArrayOf(1)) {
                    callbackRan = true
                }
            }

            assertFalse(callbackRan)
            assertEquals(0, sendCount)
        }

    /**
     * The reported bug. An included operation consumed its nonce whether or not its execution
     * reverted, so rewinding the cursor on that receipt re-reads a chain nonce the queued operation
     * behind it already owns, and the third send collides with the second.
     */
    @Test
    fun `a reverted receipt leaves the cursor where the queued operation left it`() =
        runTest {
            val first = submitter.sendTransaction(to = TARGET, data = byteArrayOf(1))
            submitter.sendTransaction(to = TARGET, data = byteArrayOf(2))

            receiptSucceeded = false
            assertFalse(submitter.awaitReceipt(first).success)
            submitter.sendTransaction(to = TARGET, data = byteArrayOf(3))

            assertEquals(listOf(0L, 1L, 2L), sentNonces)
        }

    /** A failed read is not a verdict, so the exact operation keeps ownership of its nonce. */
    @Test
    fun `a failed receipt read cannot let a later operation replace the unresolved nonce`() =
        runTest {
            val first = submitter.sendTransaction(to = TARGET, data = byteArrayOf(1))

            receiptReadFails = true
            assertFails { submitter.awaitReceipt(first) }
            receiptReadFails = false
            submitter.sendTransaction(to = TARGET, data = byteArrayOf(2))

            assertEquals(listOf(0L, 1L), sentNonces)
        }

    private fun JsonObject.userOpNonce(): Long =
        hexToBigInteger(
            this["params"]!!
                .jsonArray[0]
                .jsonObject["nonce"]!!
                .jsonPrimitive.content,
        ).toLong()

    private fun JsonObject.userOpIdentity(): TxHash {
        val operation = this["params"]!!.jsonArray[0].jsonObject

        fun field(name: String): String = operation[name]!!.jsonPrimitive.content
        val userOperation =
            UserOperationV06(
                sender = Address.parse(field("sender")),
                nonce = hexToBigInteger(field("nonce")),
                initCode = field("initCode").hexToBytes(),
                callData = field("callData").hexToBytes(),
                callGasLimit = hexToBigInteger(field("callGasLimit")),
                verificationGasLimit = hexToBigInteger(field("verificationGasLimit")),
                preVerificationGas = hexToBigInteger(field("preVerificationGas")),
                maxFeePerGas = hexToBigInteger(field("maxFeePerGas")),
                maxPriorityFeePerGas = hexToBigInteger(field("maxPriorityFeePerGas")),
                paymasterAndData = field("paymasterAndData").hexToBytes(),
                signature = field("signature").hexToBytes(),
            )
        return TxHash.fromHex("0x${userOperation.userOpHash(ENTRY_POINT, ChainId.BASE_MAINNET).toHex()}")
    }

    private fun resultFor(method: String, body: JsonObject): String =
        when (method) {
            "eth_getCode" -> {
                "\"0x00\""
            }

            "eth_call" -> {
                "\"0x${"00".repeat(WORD_BYTES)}\""
            }

            "pimlico_getUserOperationGasPrice" -> {
                """{"standard":{"maxFeePerGas":"0x1","maxPriorityFeePerGas":"0x1"}}"""
            }

            "pm_getPaymasterStubData", "pm_sponsorUserOperation" -> {
                """{"paymasterAndData":"0x"}"""
            }

            "eth_estimateUserOperationGas" -> {
                """{"preVerificationGas":"0x1","verificationGasLimit":"0x1","callGasLimit":"0x1"}"""
            }

            METHOD_SEND -> {
                val identity = if (returnWrongHash) WRONG_HASH else body.userOpIdentity()
                "\"${identity.hex}\""
            }

            METHOD_RECEIPT -> {
                receiptSucceeded?.let { succeeded ->
                    """{"success":$succeeded,"logs":[],"receipt":{"transactionHash":"0x${"cd".repeat(WORD_BYTES)}",""" +
                        """"blockNumber":"0x1","status":"0x1","gasUsed":"0x1"}}"""
                } ?: "null"
            }

            else -> {
                "null"
            }
        }

    private fun HttpRequestData.bodyText(): String = (body as TextContent).text

    private companion object {
        const val METHOD_SEND = "eth_sendUserOperation"
        const val METHOD_RECEIPT = "eth_getUserOperationReceipt"
        const val WORD_BYTES = 32
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val ENTRY_POINT: Address = Address.parse("0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789")
        val FACTORY: Address = Address.parse("0x0000000000000000000000000000000000000010")
        val SMART_ACCOUNT: Address = Address.parse("0x0000000000000000000000000000000000000011")
        val TARGET: Address = Address.parse("0x0000000000000000000000000000000000000012")
        val WRONG_HASH: TxHash = TxHash.fromHex("0x${"ff".repeat(TxHash.LEN)}")
        val RESTORED_HASH: TxHash = TxHash.fromHex("0x${"ee".repeat(TxHash.LEN)}")
    }
}
