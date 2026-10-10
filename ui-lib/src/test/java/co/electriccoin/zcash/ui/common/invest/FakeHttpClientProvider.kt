package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json

/**
 * Serves Invest's requests through [create], the client the user's Tor setting picks, configured like the
 * app's direct client (JSON, expectSuccess, retries on 5xx and on exceptions). [createTor] fails the test:
 * Invest must never choose the connection itself. [createFailure] makes [create] throw instead, as the SDK
 * does when Tor is on but can't start.
 */
internal class FakeHttpClientProvider(
    private val createFailure: Throwable? = null,
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) : HttpClientProvider {
    val requests = mutableListOf<HttpRequestData>()
    var createCalls = 0
        private set

    override suspend fun create(): HttpClient {
        createCalls++
        createFailure?.let { throw it }
        return HttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        ) {
            install(ContentNegotiation) { json() }
            install(HttpRequestRetry) {
                maxRetries = RETRIES
                retryIf { _, response -> response.status.value in 500..599 }
                retryOnExceptionIf { _, _ -> true }
                constantDelay(millis = 1)
            }
            expectSuccess = true
        }
    }

    override suspend fun createTor(): HttpClient = throw AssertionError("Invest bypassed the user's Tor setting")

    override suspend fun supportsKtorTimeouts(): Boolean = true

    companion object {
        const val RETRIES = 2
    }
}

internal fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    date: String? = null,
): HttpResponseData =
    respond(
        content = body,
        status = status,
        headers =
            if (date == null) {
                headersOf(HttpHeaders.ContentType, "application/json")
            } else {
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.Date to listOf(date))
            },
    )

internal const val TEST_MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

/** `m/44'/60'/7'/0/0` for [TEST_MNEMONIC], as viem derives it. */
internal const val TEST_ACCOUNT_ID = "0x8bae832c68d7c90c11bca5eb8bd0f14d49bd9e8f"

/** The `current_salt` view result for "252812b3": the JSON string's bytes, as NEAR RPC returns them. */
internal val SALT_RPC_RESULT: String =
    "\"252812b3\"".encodeToByteArray().joinToString(
        ",",
        prefix = "{\"jsonrpc\":\"2.0\",\"id\":\"zapp\",\"result\":{\"result\":[",
        postfix = "],\"logs\":[],\"block_height\":217339265}}"
    )
