@file:OptIn(ExperimentalSerializationApi::class)

package co.electriccoin.zcash.ui.common.invest.provider

import cash.z.ecc.android.sdk.exception.TorInitializationErrorException
import cash.z.ecc.android.sdk.exception.TorUnavailableException
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.call.body
import io.ktor.client.plugins.retry
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.justzappit.evm.intents.IntentNonce

/**
 * The 4-byte salt every intents nonce carries: `intents.near`'s `current_salt` view. Read from the same
 * public NEAR RPC list, in the same order, that `@defuse-protocol/internal-utils` uses, over whichever
 * connection the user's Tor setting picks, falling over to the next endpoint on any failure, and cached for
 * five minutes like the SDK's SaltManager. The salt rarely changes
 * (252812b3 on both 2026-09-25 and 2026-09-26), but a stale one fails every login until it is refreshed.
 */
class IntentsSaltProvider(
    private val httpClientProvider: HttpClientProvider,
    private val serverClock: InvestServerClock,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val rpcUrls: List<String> = NEAR_RPC_URLS,
) {
    private val mutex = Mutex()
    private var cached: ByteArray? = null
    private var fetchedAtMillis: Long = 0

    suspend fun salt(): ByteArray =
        mutex.withLock {
            cached?.takeIf { nowMillis() - fetchedAtMillis < CACHE_MILLIS }?.copyOf()
                ?: fetch()
                    .also {
                        cached = it
                        fetchedAtMillis = nowMillis()
                    }.copyOf()
        }

    /** Forgets the cached salt, e.g. after a login the server refused. */
    suspend fun invalidate() {
        mutex.withLock { cached = null }
    }

    // Cancellation and a Tor that can't start end the failover at once; anything else tries the next host.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun fetch(): ByteArray {
        val failures = mutableListOf<Throwable>()
        for (url in rpcUrls) {
            try {
                return fetchFrom(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TorUnavailableException) {
                // Every endpoint would fail the same way; say so instead of trying the other four.
                throw InvestApiException.TorUnavailable(e)
            } catch (e: TorInitializationErrorException) {
                throw InvestApiException.TorUnavailable(e)
            } catch (e: Exception) {
                failures += e
            }
        }
        throw InvestApiException.Unreachable(
            IllegalStateException("current_salt failed on all ${rpcUrls.size} NEAR RPC endpoints").apply {
                failures.forEach(::addSuppressed)
            },
        )
    }

    private suspend fun fetchFrom(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            httpClientProvider.create().use { client ->
                val response =
                    client.post(url) {
                        contentType(ContentType.Application.Json)
                        setBody(CURRENT_SALT_QUERY)
                        // The failover across endpoints is the retry; a stalled host must not hold the
                        // lock through the direct client's own retries first.
                        retry { noRetry() }
                    }
                serverClock.observe(response.headers[HttpHeaders.Date], fromOneClick = false)
                parseViewResult(response.body<RpcResponse>())
            }
        }

    @JsonIgnoreUnknownKeys
    @Serializable
    internal data class RpcResponse(
        @SerialName("result")
        val result: ViewResult? = null,
        @SerialName("error")
        val error: JsonObject? = null,
    )

    @JsonIgnoreUnknownKeys
    @Serializable
    internal data class ViewResult(
        @SerialName("result")
        val result: List<Int>? = null,
    )

    internal companion object {
        /** `internal-utils` 0.41.0 `PUBLIC_NEAR_RPC_URLS`, in its order. */
        val NEAR_RPC_URLS =
            listOf(
                "https://near-rpc.defuse.org",
                "https://free.rpc.fastnear.com",
                "https://1rpc.io/near",
                "https://rpc.mainnet.pagoda.co",
                "https://near.lava.build:443",
            )

        private const val CACHE_MILLIS = 300_000L
        private const val BYTE_MASK = 0xff

        private val CURRENT_SALT_QUERY =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "zapp")
                put("method", "query")
                put(
                    "params",
                    buildJsonObject {
                        put("request_type", "call_function")
                        put("finality", "final")
                        put("account_id", "intents.near")
                        put("method_name", "current_salt")
                        put("args_base64", "e30=") // {}
                    },
                )
            }

        /** The view returns the JSON-encoded string `"252812b3"` as a byte array. */
        fun parseViewResult(response: RpcResponse): ByteArray {
            val bytes = response.result?.result
            require(response.error == null && bytes != null) { "current_salt returned no result" }
            require(bytes.all { it in 0..BYTE_MASK }) { "current_salt result is not bytes" }
            val text = ByteArray(bytes.size) { bytes[it].toByte() }.decodeToString()
            val hex = (Json.parseToJsonElement(text) as? JsonPrimitive)?.takeIf { it.isString }?.content
            requireNotNull(hex) { "current_salt result is not a JSON string" }
            return IntentNonce.parseSalt(hex)
        }
    }
}
