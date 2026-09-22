// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The receipt that proves an attestation landed comes from the bundler's node; the standing is
 * read from another, which can trail it. Read at `latest` from behind the attestation, the chain
 * says the wallet is unverified with a $0 limit, and the screen would repeat that.
 */
class LivenessReaderTest {
    private val network = P2pNetworks.SEPOLIA
    private val wallet = Address.parse(WALLET)

    private val verifiedSelector = LivenessCalls.verifiedCalldata(wallet).selector()
    private val limitSelector = LivenessCalls.effectiveLimitCalldata(wallet).selector()

    /** Block tags seen on every `eth_call`, in order. */
    private val blockTags = mutableListOf<String>()

    /** How many more pinned calls the node refuses before it has the block. */
    private var callsBehind = 0

    private val engine =
        MockEngine { request ->
            val payload = Json.parseToJsonElement(request.bodyText()).jsonObject
            assertEquals("eth_call", payload["method"]!!.jsonPrimitive.content)
            val params = payload["params"]!!.jsonArray
            val data = params[0].jsonObject["data"]!!.jsonPrimitive.content
            blockTags += params[1].jsonPrimitive.content
            when {
                callsBehind > 0 -> {
                    callsBehind--
                    respond(
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"header not found"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

                data.startsWith(verifiedSelector) -> {
                    respond(rpcResult(word(1)), HttpStatusCode.OK, jsonHeaders)
                }

                data.startsWith(limitSelector) -> {
                    respond(rpcResult(word(LIMIT)), HttpStatusCode.OK, jsonHeaders)
                }

                else -> {
                    respond(rpcResult(word(CAP)), HttpStatusCode.OK, jsonHeaders)
                }
            }
        }
    private val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
    private val reader = LivenessReader(BaseRpcClient(http, "http://mock/rpc"), network, blockPollAttempts = ATTEMPTS)

    @AfterTest
    fun shutdown() {
        http.close()
    }

    @Test
    fun `a plain read asks for the latest state`() =
        runTest {
            val standing = reader.read(wallet)

            assertEquals(verifiedStanding(), standing)
            assertTrue(blockTags.all { it == "latest" }, "read at $blockTags")
        }

    @Test
    fun `a read at the attestation's block waits for a node that has not reached it`() =
        runTest {
            // The first pinned read hits a node one block behind; its refusal is retried, never
            // reported as an unverified wallet.
            callsBehind = 1

            val standing = reader.readAt(wallet, BLOCK)

            assertEquals(verifiedStanding(), standing)
            assertTrue(blockTags.all { it == BLOCK }, "read at $blockTags")
            assertTrue(blockTags.size > STANDING_CALLS, "the refused call was retried")
        }

    @Test
    fun `a node that never reaches the block fails the read rather than answering stale`() =
        runTest {
            callsBehind = Int.MAX_VALUE

            assertFailsWith<RpcException> { reader.readAt(wallet, BLOCK) }
            assertTrue(blockTags.none { it == "latest" }, "never fell back to latest")
        }

    private fun verifiedStanding() =
        LivenessStanding(isVerified = true, limit = Usdc6.ofMicros(LIMIT), tierCap = Usdc6.ofMicros(CAP))

    private fun word(value: Long): String = "0x" + bigIntegerValueOf(value).toString(16).padStart(WORD_HEX_CHARS, '0')

    private fun rpcResult(hex: String): String = """{"jsonrpc":"2.0","id":1,"result":"$hex"}"""

    private fun ByteArray.selector(): String = "0x" + copyOfRange(0, SELECTOR_BYTES).toHex()

    private fun HttpRequestData.bodyText(): String =
        (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private companion object {
        const val WALLET = "0x111111111111111111111111111111111111baaf"
        const val BLOCK = "0x2cf5a10"
        const val LIMIT = 20_000_000L
        const val CAP = 20_000_000L
        const val ATTEMPTS = 3

        /** verified, effectiveLimit, livenessTierCap. */
        const val STANDING_CALLS = 3
        const val SELECTOR_BYTES = 4
        const val WORD_HEX_CHARS = 64

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
