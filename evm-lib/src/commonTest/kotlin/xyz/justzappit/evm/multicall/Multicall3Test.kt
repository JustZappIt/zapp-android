// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.multicall

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Multicall3Test {
    @Test
    fun `the selectors are multicall3's`() {
        assertEquals("0x82ad56cb", Selector4.fromCanonicalSignature(Multicall3.AGGREGATE3).hex)
        assertEquals("0x0f28c97d", "0x" + Multicall3.currentBlockTimestamp().callData.toHex())
        assertEquals("0x42cbb15c", "0x" + Multicall3.blockNumber().callData.toHex())
        val multicall3 = Address.parse("0xcA11bde05977b3631167028862bE2a173976CA11")
        assertEquals(multicall3, Multicall3.currentBlockTimestamp().target)
        assertEquals(multicall3, Multicall3.blockNumber().target)
    }

    @Test
    fun `aggregate3 is encoded as cast encodes it, each call with its own allowFailure`() {
        val calls =
            listOf(
                Multicall3Call(SWAPS, AbiEncoder.encodeFunctionCall("getSwap(bytes32)", listOf(AbiBytes32(ID)))),
                Multicall3.currentBlockTimestamp(),
                Multicall3Call(Multicall3.ADDRESS, Multicall3.blockNumber().callData, allowFailure = true),
            )

        assertEquals(AGGREGATE3_CALLDATA, "0x" + Multicall3.encodeAggregate3(calls).toHex())
        assertEquals(EMPTY_CALLDATA, "0x" + Multicall3.encodeAggregate3(emptyList()).toHex())
    }

    @Test
    fun `an answer reads as each call's success and return data, in order`() {
        val results = Multicall3.decodeAggregate3(ANSWER.hexToBytes(), calls = 3)

        assertEquals(
            listOf(
                Multicall3Result(true, ByteArray(31) + byteArrayOf(1)),
                Multicall3Result(false, "0x08c379a0".hexToBytes()),
                Multicall3Result(true, ByteArray(0)),
            ),
            results,
        )
        assertEquals(bigIntegerValueOf(1), results[0].uint())
        assertFailsWith<IllegalArgumentException> { results[1].uint() }
        assertFailsWith<IllegalArgumentException> { results[2].uint() }
        assertEquals(emptyList(), Multicall3.decodeAggregate3(EMPTY_ANSWER.hexToBytes(), calls = 0))
    }

    @Test
    fun `an answer to another number of calls, or one that reads past itself, never reads`() {
        val answer = ANSWER.hexToBytes()
        val malformed =
            listOf(
                // Another number of results than calls.
                answer to 2,
                answer to 4,
                // Cut short anywhere: in the head, in an element, in a result's bytes.
                answer.copyOf(31) to 3,
                answer.copyOf(5 * 32) to 3,
                answer.copyOf(answer.size - 32) to 3,
                // An array offset, an element offset and a length that lie past the answer.
                answer.withWord(0, answer.size + 32L) to 3,
                answer.withWord(2, answer.size.toLong()) to 3,
                answer.withWord(6, 1L shl 40) to 3,
                // A success flag that isn't a bool.
                answer.withWord(5, 2) to 3,
                ByteArray(0) to 0,
            )
        for ((data, calls) in malformed) {
            assertFailsWith<IllegalArgumentException>("${data.size} bytes, $calls calls") {
                Multicall3.decodeAggregate3(data, calls)
            }
        }
    }

    @Test
    fun `a read runs every call in one eth_call to multicall3 at the block asked for`() =
        runTest {
            var request: JsonObject? = null
            val http =
                RpcHttpClient.create(
                    engine =
                        MockEngine { sent ->
                            val body = (sent.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                            request = Json.parseToJsonElement(body).jsonObject
                            respond(
                                """{"jsonrpc":"2.0","id":1,"result":"$ANSWER"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    config = RpcHttpClient.Config(maxRetries = 0),
                )
            val calls = List(3) { Multicall3.currentBlockTimestamp() }

            val results = BaseRpcClient(http, "http://mock/rpc").aggregate3(calls, blockTag = "0x10")

            assertEquals(3, results.size)
            val sent = checkNotNull(request)
            assertEquals("eth_call", sent["method"]!!.jsonPrimitive.content)
            val params = sent["params"]!!.jsonArray
            val call = params[0].jsonObject
            assertEquals(Multicall3.ADDRESS.checksumHex, call["to"]!!.jsonPrimitive.content)
            assertContentEquals(Multicall3.encodeAggregate3(calls), call["data"]!!.jsonPrimitive.content.hexToBytes())
            assertEquals("0x10", params[1].jsonPrimitive.content)
            http.close()
        }

    private companion object {
        val SWAPS = Address.parse("0x459b0a69e0f736666b66a3ffe0fc17c11f05980d")
        val ID = ByteArray(32).apply { fill(0x5c) }

        // `cast calldata "aggregate3((address,bool,bytes)[])"` with these three calls.
        const val AGGREGATE3_CALLDATA =
            "0x82ad56cb00000000000000000000000000000000000000000000000000000000000000200000000000000000000000" +
                "000000000000000000000000000000000000000003000000000000000000000000000000000000000000000000000000" +
                "000000006000000000000000000000000000000000000000000000000000000000000001200000000000000000000000" +
                "0000000000000000000000000000000000000001c0000000000000000000000000459b0a69e0f736666b66a3ffe0fc17" +
                "c11f05980d00000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000060000000000000000000000000000000000000000000000000000000" +
                "00000000243da0e66e5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c00000000000000" +
                "000000000000000000000000000000000000000000000000000000000000000000ca11bde05977b3631167028862be2a" +
                "173976ca1100000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000060000000000000000000000000000000000000000000000000000000" +
                "00000000040f28c97d000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "00ca11bde05977b3631167028862be2a173976ca11000000000000000000000000000000000000000000000000000000" +
                "000000000100000000000000000000000000000000000000000000000000000000000000600000000000000000000000" +
                "00000000000000000000000000000000000000000442cbb15c0000000000000000000000000000000000000000000000" +
                "0000000000"

        // `cast calldata "aggregate3((address,bool,bytes)[])" "[]"`.
        const val EMPTY_CALLDATA =
            "0x82ad56cb00000000000000000000000000000000000000000000000000000000000000200000000000000000000000" +
                "000000000000000000000000000000000000000000"

        // `cast abi-encode "f((bool,bytes)[])" "[(true,0x00…01),(false,0x08c379a0),(true,0x)]"`.
        const val ANSWER =
            "0x0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000" +
                "000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000000" +
                "6000000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000000000" +
                "000000000000000000000000000000016000000000000000000000000000000000000000000000000000000000000000" +
                "010000000000000000000000000000000000000000000000000000000000000040000000000000000000000000000000" +
                "000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000" +
                "010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000" +
                "0408c379a000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000" +
                "400000000000000000000000000000000000000000000000000000000000000000"

        // `cast abi-encode "f((bool,bytes)[])" "[]"`.
        const val EMPTY_ANSWER =
            "0x0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000" +
                "0000000000000000000000000000000000"

        /** [this] answer with its word [index] replaced by [value]. */
        fun ByteArray.withWord(
            index: Int,
            value: Long,
        ): ByteArray {
            val word = bigIntegerValueOf(value).toByteArray().let { ByteArray(32 - it.size) + it }
            return copyOf().also { word.copyInto(it, index * 32) }
        }
    }
}
