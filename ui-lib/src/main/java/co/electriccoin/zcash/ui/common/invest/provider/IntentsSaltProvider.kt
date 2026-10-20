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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.TimeoutException

/**
 * `intents.near`'s `current_salt`, read from the intents SDK's public NEAR RPC hosts in order, failing over to the
 * next on any error, and cached for five minutes like the SDK does.
 */
class IntentsSaltProvider(
    private val httpClientProvider: HttpClientProvider,
    private val serverClock: InvestServerClock,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val rpcUrls: List<String> = NEAR_RPC_URLS,
    private val perHostTimeoutMillis: Long = PER_HOST_TIMEOUT_MILLIS,
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

    suspend fun invalidate() {
        mutex.withLock { cached = null }
    }

    // Each host is bounded, as the Tor client has no timeouts and a stalled host would hold this lock.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun fetch(): ByteArray {
        val failures = mutableListOf<Throwable>()
        for (url in rpcUrls) {
            try {
                val salt = fetchFrom(url)
                if (salt != null) return salt
                failures += TimeoutException("$url didn't answer within $perHostTimeoutMillis ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TorUnavailableException) {
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

    /** Null when [url] didn't answer in time. */
    private suspend fun fetchFrom(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(perHostTimeoutMillis) { fetchSalt(url) }
        }

    private suspend fun fetchSalt(url: String): ByteArray =
        httpClientProvider.create().use { client ->
            val response =
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(CURRENT_SALT_QUERY)
                    // Failing over to the next host is the retry.
                    retry { noRetry() }
                }
            serverClock.observe(response.headers[HttpHeaders.Date], fromOneClick = false)
            parseViewResult(response.body<RpcResponse>())
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
        /** The intents SDK's `PUBLIC_NEAR_RPC_URLS`, in its order. */
        val NEAR_RPC_URLS =
            listOf(
                "https://near-rpc.defuse.org",
                "https://free.rpc.fastnear.com",
                "https://1rpc.io/near",
                "https://rpc.mainnet.pagoda.co",
                "https://near.lava.build:443",
            )

        private const val CACHE_MILLIS = 300_000L
        const val PER_HOST_TIMEOUT_MILLIS = 20_000L
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

        /** The view returns a JSON string such as `"252812b3"`, as bytes. */
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
