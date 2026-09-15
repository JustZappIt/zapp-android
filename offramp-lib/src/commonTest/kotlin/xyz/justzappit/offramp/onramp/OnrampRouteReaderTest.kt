// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

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
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.liveness.LivenessCalls
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reputation.ReputationCalls
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One read, both contracts — and on a network with no integrator, one contract and nothing else.
 * The mainnet case is the one that matters: it must cost exactly what the reputation-only read
 * cost before, and never ask an address that is null for a limit.
 */
class OnrampRouteReaderTest {
    /** `(to, selector)` of every eth_call, in order. */
    private val calls = mutableListOf<Pair<String, String>>()
    private var failIntegratorReads = false

    private val userTxLimitSelector = ReputationCalls.userTxLimitCalldata(WALLET, CurrencyCode.Inr).selector()
    private val effectiveLimitSelector = LivenessCalls.effectiveLimitCalldata(WALLET).selector()
    private val remainingSelector = LivenessCalls.remainingDailyCountCalldata(WALLET).selector()
    private val pausedSelector = LivenessCalls.pausedCalldata().selector()
    private var paused = false

    private val rpcHttp =
        HttpClient(
            MockEngine { request ->
                val call =
                    Json
                        .parseToJsonElement(request.bodyText())
                        .jsonObject
                        .getValue("params")
                        .jsonArray[0]
                        .jsonObject
                val to =
                    call
                        .getValue("to")
                        .jsonPrimitive.content
                        .lowercase()
                val selector =
                    call
                        .getValue("data")
                        .jsonPrimitive.content
                        .substring(0, SELECTOR_HEX_LEN)
                calls += to to selector
                val result =
                    when (selector) {
                        userTxLimitSelector -> word(DIRECT_MICROS) + word(0)
                        effectiveLimitSelector -> if (failIntegratorReads) null else word(INTEGRATOR_MICROS)
                        remainingSelector -> if (failIntegratorReads) null else word(REMAINING)
                        pausedSelector -> if (failIntegratorReads) null else word(if (paused) 1 else 0)
                        else -> error("Unexpected eth_call: $selector")
                    }
                val body =
                    if (result == null) {
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}"""
                    } else {
                        """{"jsonrpc":"2.0","id":1,"result":"0x$result"}"""
                    }
                respond(body, HttpStatusCode.OK, JSON)
            },
        ) { install(ContentNegotiation) { json() } }

    private val rpc = BaseRpcClient(rpcHttp, "http://mock/rpc")

    @AfterTest
    fun shutdown() {
        rpcHttp.close()
    }

    @Test
    fun `with an integrator, both limits, the daily count and the switch come back from one pass`() =
        runTest {
            paused = true

            val limits = OnrampRouteReader(rpc, P2pNetworks.SEPOLIA).read(WALLET, CurrencyCode.Inr)

            assertEquals(Usdc6.ofMicros(DIRECT_MICROS), limits.direct)
            assertEquals(Usdc6.ofMicros(INTEGRATOR_MICROS), limits.integrator)
            assertEquals(bigIntegerValueOf(REMAINING), limits.integratorOrdersRemaining)
            assertTrue(limits.integratorPaused)
            assertEquals(4, calls.size)
            assertEquals(
                setOf(
                    P2pNetworks.SEPOLIA.diamondAddress.lowercaseHex to userTxLimitSelector,
                    P2pNetworks.SEPOLIA_LIVENESS_INTEGRATOR.lowercase() to effectiveLimitSelector,
                    P2pNetworks.SEPOLIA_LIVENESS_INTEGRATOR.lowercase() to remainingSelector,
                    P2pNetworks.SEPOLIA_LIVENESS_INTEGRATOR.lowercase() to pausedSelector,
                ),
                calls.toSet(),
            )
        }

    @Test
    fun `without an integrator, only the diamond is asked`() =
        runTest {
            val mainnet = P2pNetworks.mainnet(rpcUrl = "http://mock/rpc", subgraphUrl = "http://mock/graph")

            val limits = OnrampRouteReader(rpc, mainnet).read(WALLET, CurrencyCode.Inr)

            assertEquals(Usdc6.ofMicros(DIRECT_MICROS), limits.direct)
            assertEquals(Usdc6.ZERO, limits.integrator)
            assertEquals(bigIntegerZero, limits.integratorOrdersRemaining)
            assertFalse(limits.integratorPaused)
            assertEquals(listOf(mainnet.diamondAddress.lowercaseHex to userTxLimitSelector), calls)
        }

    @Test
    fun `one failed read fails the whole thing rather than zeroing a limit`() =
        runTest {
            // A zeroed integrator limit would route a verified wallet as though it were cold.
            failIntegratorReads = true

            assertFailsWith<Exception> { OnrampRouteReader(rpc, P2pNetworks.SEPOLIA).read(WALLET, CurrencyCode.Inr) }
        }

    private fun ByteArray.selector(): String = "0x" + copyOfRange(0, SELECTOR_BYTES).joinToString("") { it.hex() }

    private fun Byte.hex(): String = toUByte().toString(HEX_RADIX).padStart(2, '0')

    private fun word(value: Long): String = value.toString(HEX_RADIX).padStart(WORD_HEX_LEN, '0')

    private fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private companion object {
        val WALLET: Address = Address.parse("0x448f857ea117138e85d062c6ce89e90a337874d6")
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
        const val DIRECT_MICROS = 50_000_000L
        const val INTEGRATOR_MICROS = 20_000_000L
        const val REMAINING = 4L
        const val SELECTOR_BYTES = 4
        const val SELECTOR_HEX_LEN = 10
        const val WORD_HEX_LEN = 64
        const val HEX_RADIX = 16
    }
}
