package co.electriccoin.zcash.ui.common.provider

import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json

interface HttpClientProvider {
    suspend fun create(): HttpClient

    /**
     * Returns a client that always routes over Tor, regardless of the user's Tor preference. Used for
     * requests that must never touch clearnet (e.g. exchange-rate fetching, MOB-1378).
     */
    suspend fun createTor(): HttpClient

    suspend fun supportsKtorTimeouts(): Boolean = true
}

class HttpClientProviderImpl(
    private val synchronizerProvider: SynchronizerProvider,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider
) : HttpClientProvider {
    override suspend fun create(): HttpClient =
        if (isTorEnabledStorageProvider.get() == true) createTor() else createDirect()

    override suspend fun supportsKtorTimeouts(): Boolean = isTorEnabledStorageProvider.get() != true

    override suspend fun createTor() =
        synchronizerProvider
            .getSynchronizer()
            .getTorHttpClient {
                configureHttpClient(installTimeouts = false)
            }

    @Suppress("MagicNumber")
    private fun createDirect() =
        HttpClient(OkHttp) {
            configureHttpClient(installTimeouts = true)
            engine {
                // MOB-1378: Currency Conversion exchange rates must only ever be fetched over Tor (the
                // in-app copy promises rates are fetched over Tor to protect the user's IP). Pricing
                // providers route through createTor(), so this clearnet client should never see one of
                // their requests. This interceptor is a backstop against leaking an IP or request timing.
                addInterceptor { chain ->
                    if (chain
                            .request()
                            .url.host
                            .isExchangeRateHost()
                    ) {
                        throw ClearnetExchangeRateBlockedError()
                    }
                    chain.proceed(chain.request())
                }
            }
            install(HttpRequestRetry) {
                maxRetries = MAX_RETRIES
                retryIf { request, response ->
                    !request.url.toString().isVotingHelperPath() &&
                        !request.url.host.isExchangeRateHost() &&
                        response.status.value in 500..599
                }
                retryOnExceptionIf { request, _ ->
                    !request.url.toString().isVotingHelperPath() &&
                        !request.url.host.isExchangeRateHost()
                }
                exponentialDelay()
            }
        }

    private fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureHttpClient(
        installTimeouts: Boolean
    ) {
        install(ContentNegotiation) { json() }
        // arti manages its own circuit timeouts for the Tor client — a Ktor timeout aborts
        // offramp/CMC requests over Tor early — so install Ktor timeouts only on the direct
        // (clearnet) client, where an absent timeout would let a request hang indefinitely.
        if (installTimeouts) {
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS
            }
        }
        install(Logging) {
            logger = KtorLogger()
            // Request and response bodies contain wallet addresses, payment instructions, and
            // amounts. Keep them out of logcat in every build; debug logs retain only request
            // metadata, with sensitive query parameters redacted by KtorLogger.
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
            sanitizeHeader { header -> header in SANITIZED_HEADERS }
        }
        expectSuccess = true
    }
}

private fun String.isVotingHelperPath(): Boolean =
    contains("/shielded-vote/v1/shares") ||
        contains("/shielded-vote/v1/share-status/")

// MOB-1378: pricing requests must never leave over the direct (clearnet) client. Match exact hosts so
// a path or query that happens to contain one of them cannot trigger a false positive.
internal fun String.isExchangeRateHost(): Boolean = this == CMC_API_HOST || this == PRICING_ENGINE_HOST

internal const val PRICING_ENGINE_HOST = "zapp-pricing-engine.majorworker.workers.dev"

/**
 * MOB-1378: raised by the direct (clearnet) client's backstop interceptor when an exchange-rate request
 * would leave over clearnet. This is an unrecoverable invariant violation - a caller regressing from
 * createTor() to create() for the CMC host - not a network condition to recover from. Extends
 * [AssertionError] (an `Error`, not an `Exception`) so it is NOT swallowed by the `catch (Exception)`
 * fallbacks downstream (e.g. ExchangeRateRepository) and instead crashes the app loudly. OkHttp's async
 * dispatch rethrows non-IOException throwables on its dispatcher thread, so this propagates to the
 * uncaught-exception handler rather than corrupting the call.
 */
class ClearnetExchangeRateBlockedError :
    AssertionError(
        "Exchange rate fetching over clearnet is not allowed while Tor is disabled"
    )

private class KtorLogger : Logger {
    override fun log(message: String) {
        sanitizeHttpLogMessage(message).chunked(MAX_LOG_CHUNK).forEach { Log.d("HttpClient", it) }
    }

    private companion object {
        const val MAX_LOG_CHUNK = 3900
    }
}

internal fun sanitizeHttpLogMessage(message: String): String =
    SENSITIVE_QUERY_PARAMETER.replace(message) { match -> "${match.groupValues[1]}=<redacted>" }

private const val MAX_RETRIES = 4
private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 120_000L
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L

private val SENSITIVE_QUERY_PARAMETER = Regex("(?i)(depositAddress|recipient|refundTo|refundAddress)=[^&\\s]+")

// Credential-bearing headers redacted from logs even in debug. The shared client also serves the
// CMC quote API (X-CMC_PRO_API_KEY); X-Helper-Token is upstream's voting-helper credential.
private val SANITIZED_HEADERS = setOf(HttpHeaders.Authorization, "X-CMC_PRO_API_KEY", "X-Helper-Token")
