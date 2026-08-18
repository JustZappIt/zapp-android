// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException

object RpcHttpClient {
    data class Config(
        val connectTimeoutMillis: Long = 10_000L,
        val requestTimeoutMillis: Long = 30_000L,
        val socketTimeoutMillis: Long = 30_000L,
        val maxRetries: Int = 3,
        val maxBackoffMillis: Long = 5_000L,
        val randomJitterMillis: Long = 100L,
        /**
         * Optional ktor [Logging] sink. Defaults to null (off) so jvmTest mock-engine tests stay
         * quiet; production wiring in `ProviderModule` passes a Twig-backed logger so subgraph +
         * RPC failures appear in logcat alongside the rest of the app's HTTP traffic.
         */
        val logger: Logger? = null,
        val logLevel: LogLevel = LogLevel.INFO,
    )

    fun create(config: Config = Config()): HttpClient = createDefaultRpcHttpClient(config)

    fun <T : HttpClientEngineConfig> create(
        engineFactory: HttpClientEngineFactory<T>,
        config: Config = Config(),
        engineBlock: T.() -> Unit = {},
    ): HttpClient =
        HttpClient(engineFactory) {
            engine(engineBlock)
            applyRpcDefaults(config)
        }

    fun create(engine: HttpClientEngine, config: Config = Config()): HttpClient =
        HttpClient(engine) { applyRpcDefaults(config) }

    internal fun HttpClientConfig<*>.applyRpcDefaults(config: Config) {
        install(ContentNegotiation) { json() }
        config.logger?.let { sink ->
            install(Logging) {
                logger = sink
                level = config.logLevel
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMillis
            requestTimeoutMillis = config.requestTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }
        install(HttpRequestRetry) {
            maxRetries = config.maxRetries
            retryOnExceptionIf { _, cause -> isTransientTransportError(cause) }
            retryIf { _, response ->
                val status = response.status.value
                status in SERVER_ERROR_RANGE || response.status == HttpStatusCode.TooManyRequests
            }
            exponentialDelay(
                base = 2.0,
                maxDelayMs = config.maxBackoffMillis,
                randomizationMs = config.randomJitterMillis,
            )
        }
    }

    private fun isTransientTransportError(cause: Throwable): Boolean =
        when (cause) {
            is ConnectTimeoutException -> true
            is SocketTimeoutException -> true
            is IOException -> true
            else -> false
        }

    private val SERVER_ERROR_RANGE = 500..599
}

internal expect fun createDefaultRpcHttpClient(config: RpcHttpClient.Config): HttpClient
