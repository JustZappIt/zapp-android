package co.electriccoin.zcash.ui.common.invest

import cash.z.ecc.android.sdk.exception.TorUnavailableException
import co.electriccoin.zcash.ui.common.invest.provider.IntentsSaltProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Fixtures are real 1Click and NEAR RPC bodies, kept on one line to match what the wire carries.
@Suppress("MaxLineLength")
class IntentsSaltProviderTest {
    @Test
    fun `reads current_salt from intents near and decodes the view's JSON string`() =
        runTest {
            val http = FakeHttpClientProvider { jsonResponse(SALT_RPC_RESULT) }

            val salt = IntentsSaltProvider(http, InvestServerClock()).salt()

            assertEquals(listOf(0x25, 0x28, 0x12, 0xb3), salt.map { it.toInt() and 0xff })
            val request = http.requests.single()
            assertEquals("https://near-rpc.defuse.org", request.url.toString())
            val params =
                Json
                    .parseToJsonElement((request.body as TextContent).text)
                    .jsonObject
                    .getValue("params")
                    .jsonObject
            assertEquals("intents.near", params.getValue("account_id").jsonPrimitive.content)
            assertEquals("current_salt", params.getValue("method_name").jsonPrimitive.content)
            assertEquals("final", params.getValue("finality").jsonPrimitive.content)
        }

    @Test
    fun `falls over to the next endpoint in the SDK's order`() =
        runTest {
            val http =
                FakeHttpClientProvider { request ->
                    when (request.url.host) {
                        "near-rpc.defuse.org" -> throw IOException("down")
                        "free.rpc.fastnear.com" -> jsonResponse("{}", HttpStatusCode.ServiceUnavailable)
                        else -> jsonResponse(SALT_RPC_RESULT)
                    }
                }

            IntentsSaltProvider(http, InvestServerClock()).salt()

            // One attempt per endpoint: the failover is the retry, not the client's retry plugin.
            assertEquals(
                listOf("near-rpc.defuse.org", "free.rpc.fastnear.com", "1rpc.io"),
                http.requests.map { it.url.host },
            )
        }

    @Test
    fun `caches for five minutes, then asks again`() =
        runTest {
            var now = 0L
            val http = FakeHttpClientProvider { jsonResponse(SALT_RPC_RESULT) }
            val provider = IntentsSaltProvider(http, InvestServerClock(), nowMillis = { now })

            provider.salt()
            now = 299_999
            provider.salt()
            assertEquals(1, http.requests.size)

            now = 300_000
            provider.salt()
            assertEquals(2, http.requests.size)

            provider.invalidate()
            provider.salt()
            assertEquals(3, http.requests.size)
        }

    @Test
    fun `a returned salt can't be mutated into the cache`() =
        runTest {
            val provider = IntentsSaltProvider(FakeHttpClientProvider { jsonResponse(SALT_RPC_RESULT) }, InvestServerClock())

            provider.salt().fill(0)

            assertEquals(0x25, provider.salt()[0].toInt())
        }

    @Test
    fun `stops at the first endpoint when Tor can't start`() =
        runTest {
            val http = FakeHttpClientProvider(TorUnavailableException()) { error("unreached") }

            assertFailsWith<InvestApiException.TorUnavailable> { IntentsSaltProvider(http, InvestServerClock()).salt() }
            assertEquals(1, http.createCalls)
        }

    @Test
    fun `fails as Unreachable when every endpoint fails or answers nonsense`() =
        runTest {
            val bodies =
                listOf(
                    """{"error":{"name":"HANDLER_ERROR"}}""",
                    """{"result":{"result":[34,122,122,34]}}""", // "zz": not 8 hex characters
                    """{"result":{"result":[300]}}""",
                    """{"result":{}}""",
                    // A contract panic comes back inside result, not as an RPC error.
                    """{"jsonrpc":"2.0","id":"zapp","result":{"error":"wasm execution failed","logs":[]}}""",
                )
            bodies.forEach { body ->
                val http = FakeHttpClientProvider { jsonResponse(body) }
                assertFailsWith<InvestApiException.Unreachable>(body) { IntentsSaltProvider(http, InvestServerClock()).salt() }
                assertEquals(IntentsSaltProvider.NEAR_RPC_URLS.size, http.createCalls, body)
            }
        }
}
