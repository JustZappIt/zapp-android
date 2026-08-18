// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.toHex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseRpcClientTest {
    private val handledRequests = mutableListOf<JsonObject>()
    private var nextResponse: String = ""
    private var nextStatus: HttpStatusCode = HttpStatusCode.OK
    private var nextHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private var nextThrow: Throwable? = null

    private val client =
        RpcHttpClient.create(
            engine =
                MockEngine { request ->
                    nextThrow?.let { throw it }
                    val bodyBytes = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                    handledRequests += Json.parseToJsonElement(bodyBytes.decodeToString()) as JsonObject
                    respond(content = nextResponse, status = nextStatus, headers = nextHeaders)
                },
            config = RpcHttpClient.Config(maxRetries = 0),
        )

    private val rpc = BaseRpcClient(client, "http://mock/rpc")

    @AfterTest
    fun shutdown() {
        client.close()
    }

    @Test
    fun `HTTP 429 surfaces as RpcException RateLimited with parsed Retry-After`() =
        runTest {
            nextStatus = HttpStatusCode.TooManyRequests
            nextHeaders = headersOf(HttpHeaders.RetryAfter, "2")
            val e = assertFailsWith<RpcException.RateLimited> { rpc.ethChainId() }
            assertEquals("eth_chainId", e.method)
            assertEquals(2_000L, e.retryAfterMillis)
        }

    @Test
    fun `transport IOException surfaces as RpcException TransportError`() =
        runTest {
            nextThrow = IOException("connection reset")
            val e = assertFailsWith<RpcException.TransportError> { rpc.ethChainId() }
            assertEquals("eth_chainId", e.method)
        }

    @Test
    fun `ethChainId decodes hex result to ChainId`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":"0x14a34"}"""
            val chainId = rpc.ethChainId()
            assertEquals(xyz.justzappit.evm.types.ChainId.BASE_SEPOLIA, chainId)
            assertEquals("eth_chainId", handledRequests.last()["method"]!!.jsonPrimitive.content)
        }

    @Test
    fun `ethGasPrice decodes hex to Wei`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":"0x3b9aca00"}"""
            assertEquals(Wei(BigInteger("1000000000")), rpc.ethGasPrice())
        }

    @Test
    fun `ethGetTransactionCount sends address and tag`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""
            val address = Address.parse("0x0000000000000000000000000000000000000abc")
            val nonce = rpc.ethGetTransactionCount(address, blockTag = "latest")
            assertEquals(bigIntegerValueOf(42), nonce.value)
            val params = handledRequests.last()["params"]!!.toString()
            assertTrue(params.contains(address.checksumHex))
            assertTrue(params.contains("latest"))
        }

    @Test
    fun `ethCall hex-encodes data with 0x prefix and decodes returned bytes`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":"0xdeadbeef"}"""
            val to = Address.parse("0x0000000000000000000000000000000000000010")
            val result = rpc.ethCall(to, byteArrayOf(0x01, 0x02))
            assertEquals("deadbeef", result.toHex())
            val sentParams = handledRequests.last()["params"]!!.toString()
            assertTrue(sentParams.contains("\"0x0102\""), "expected hex-encoded data in params, got $sentParams")
        }

    @Test
    fun `ethEstimateGas sends from to value data tuple`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":"0x5208"}"""
            val from = Address.parse("0x000000000000000000000000000000000000F70F")
            val to = Address.parse("0x0000000000000000000000000000000000000010")
            val gas = rpc.ethEstimateGas(from = from, to = to, value = Wei.ofLong(1_000))
            assertEquals(bigIntegerValueOf(21_000), gas.value)
        }

    @Test
    fun `ethSendRawTransaction prepends 0x when missing and parses result into TxHash`() =
        runTest {
            val resultHex = "0x" + "ab".repeat(32)
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":"$resultHex"}"""
            assertEquals(TxHash.fromHex(resultHex), rpc.ethSendRawTransaction("aabbcc"))
            val params = handledRequests.last()["params"]!!.toString()
            assertTrue(params.contains("0xaabbcc"))
        }

    @Test
    fun `ethGetTransactionReceipt returns null when result is null`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":null}"""
            assertNull(rpc.ethGetTransactionReceipt(TxHash.fromHex("0x" + "01".repeat(32))))
        }

    @Test
    fun `ethGetTransactionReceipt parses success`() =
        runTest {
            val txHex = "0x" + "01".repeat(32)
            nextResponse =
                """
                {"jsonrpc":"2.0","id":1,"result":{
                  "transactionHash":"$txHex",
                  "blockNumber":"0x10",
                  "status":"0x1",
                  "gasUsed":"0x5208",
                  "effectiveGasPrice":"0x3b9aca00",
                  "logs":[]
                }}
                """.trimIndent()
            val receipt = rpc.ethGetTransactionReceipt(TxHash.fromHex(txHex))!!
            assertEquals(txHex, receipt.transactionHash)
            assertTrue(receipt.success)
            assertEquals("0x5208", receipt.gasUsed)
        }

    @Test
    fun `ethGetBlockByNumber returns baseFeePerGas when present`() =
        runTest {
            nextResponse =
                """
                {"jsonrpc":"2.0","id":1,"result":{
                  "number":"0x100",
                  "timestamp":"0x68000000",
                  "baseFeePerGas":"0x1"
                }}
                """.trimIndent()
            val block = rpc.ethGetBlockByNumber()
            assertEquals("0x100", block.number)
            assertEquals("0x1", block.baseFeePerGas)
        }

    @Test
    fun `code 3 with no data is classified as ExecutionReverted`() =
        runTest {
            nextResponse =
                """
                {"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}
                """.trimIndent()
            val ex = assertFailsWith<RpcException.ExecutionReverted> { rpc.ethGasPrice() }
            assertEquals("eth_gasPrice", ex.method)
            assertNull(ex.selector)
            assertNull(ex.solidityErrorString)
        }

    @Test
    fun `vendor -32000 plus reverted message is classified as ExecutionReverted`() =
        runTest {
            nextResponse =
                """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"execution reverted: 0x91da284f"}}
                """.trimIndent()
            val ex = assertFailsWith<RpcException.ExecutionReverted> { rpc.ethGasPrice() }
            assertEquals("eth_gasPrice", ex.method)
            // No data field — selector cannot be recovered from the message string at this layer.
            assertNull(ex.selector)
        }

    @Test
    fun `ExecutionReverted carries selector and decoded Error string when data is present`() =
        runTest {
            val payload =
                "0x08c379a0" +
                    "0000000000000000000000000000000000000000000000000000000000000020" +
                    "0000000000000000000000000000000000000000000000000000000000000005" +
                    "68656c6c6f000000000000000000000000000000000000000000000000000000"
            nextResponse =
                """
                {"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"$payload"}}
                """.trimIndent()
            val ex = assertFailsWith<RpcException.ExecutionReverted> { rpc.ethGasPrice() }
            assertEquals("hello", ex.solidityErrorString)
            assertEquals("0x08c379a0", ex.selector?.hex)
        }

    @Test
    fun `unknown JSON-RPC error code maps to RpcException Unknown`() =
        runTest {
            nextResponse =
                """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32700,"message":"Parse error"}}
                """.trimIndent()
            val ex = assertFailsWith<RpcException.Unknown> { rpc.ethGasPrice() }
            assertEquals("eth_gasPrice", ex.method)
            assertEquals(-32_700, ex.code)
        }

    @Test
    fun `method not found maps to RpcException MethodNotFound`() =
        runTest {
            nextResponse =
                """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}
                """.trimIndent()
            val ex = assertFailsWith<RpcException.MethodNotFound> { rpc.ethGasPrice() }
            assertEquals("eth_gasPrice", ex.method)
        }
}
