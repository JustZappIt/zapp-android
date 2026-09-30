// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignedTransactionsTest {
    private val methods = mutableListOf<String>()
    private var sendAnswer = """"$HASH""""
    private var known = false
    private var receipt: Pair<Long, String>? = null

    private val client =
        RpcHttpClient.create(
            engine =
                MockEngine { request ->
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val method =
                        Json
                            .parseToJsonElement(body)
                            .jsonObject
                            .getValue("method")
                            .jsonPrimitive.content
                    methods += method
                    val answer =
                        when (method) {
                            "eth_sendRawTransaction" -> sendAnswer
                            "eth_getTransactionByHash" -> if (known) """{"hash":"$HASH"}""" else "null"
                            "eth_getTransactionReceipt" -> receiptJson()
                            "eth_getBlockByNumber" -> """{"number":"0x${HEAD.toString(16)}","timestamp":"0x1"}"""
                            else -> error("unexpected $method")
                        }
                    val content =
                        if (answer.startsWith("error:")) {
                            """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"${answer.drop(6)}"}}"""
                        } else {
                            """{"jsonrpc":"2.0","id":1,"result":$answer}"""
                        }
                    respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            config = RpcHttpClient.Config(maxRetries = 0),
        )
    private val rpc = BaseRpcClient(client, "http://mock/rpc")

    @AfterTest
    fun shutdown() {
        client.close()
    }

    @Test
    fun `a transaction the node takes is sent once`() =
        runTest {
            rpc.sendSignedTransaction(RAW, TxHash.fromHex(HASH))

            assertEquals(listOf("eth_sendRawTransaction"), methods)
        }

    @Test
    fun `a refusal from a node that has the transaction already is no failure`() =
        runTest {
            sendAnswer = "error:already known"
            known = true

            rpc.sendSignedTransaction(RAW, TxHash.fromHex(HASH))

            assertEquals(listOf("eth_sendRawTransaction", "eth_getTransactionByHash"), methods)
        }

    @Test
    fun `a refusal from a node that doesn't have the transaction stands`() =
        runTest {
            sendAnswer = "error:insufficient funds for gas * price + value"

            assertFailsWith<RpcException.Unknown> { rpc.sendSignedTransaction(RAW, TxHash.fromHex(HASH)) }
        }

    @Test
    fun `bytes that aren't the transaction their hash names are never sent`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                rpc.sendSignedTransaction(RAW, TxHash.fromHex("0x" + "ab".repeat(32)))
            }
            assertEquals(emptyList(), methods)
        }

    @Test
    fun `a transaction counts as in a block once that block has enough on top`() =
        runTest {
            val hash = TxHash.fromHex(HASH)

            assertEquals(TransactionStatus.UNKNOWN, rpc.transactionStatus(hash, confirmations = 2))
            known = true
            assertEquals(TransactionStatus.PENDING, rpc.transactionStatus(hash, confirmations = 2))
            receipt = HEAD to "0x1"
            assertEquals(TransactionStatus.PENDING, rpc.transactionStatus(hash, confirmations = 2))
            assertEquals(TransactionStatus.CONFIRMED, rpc.transactionStatus(hash, confirmations = 1))
            receipt = HEAD - 1 to "0x0"
            assertEquals(TransactionStatus.REVERTED, rpc.transactionStatus(hash, confirmations = 2))
        }

    private fun receiptJson(): String =
        receipt?.let { (block, status) ->
            """{"transactionHash":"$HASH","blockNumber":"0x${block.toString(16)}","status":"$status","gasUsed":"0x1"}"""
        } ?: "null"

    private companion object {
        const val RAW = "0x02f8"
        const val HEAD = 1_000L
        val HASH = TxHash(keccak256(RAW.hexToBytes())).hex
    }
}
