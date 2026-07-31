// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RpcHttpClientTest {
    @Test
    fun `retries on 5xx and eventually succeeds`() =
        runTest {
            val attempts = AtomicInteger(0)
            val engine =
                MockEngine { _ ->
                    val n = attempts.incrementAndGet()
                    if (n < 3) respondError(HttpStatusCode.InternalServerError) else respond("ok")
                }
            val client =
                RpcHttpClient.create(
                    engine,
                    RpcHttpClient.Config(maxRetries = 4, maxBackoffMillis = 10, randomJitterMillis = 0),
                )
            val body = client.get("http://test/x").bodyAsText()
            assertEquals("ok", body)
            assertEquals(3, attempts.get())
        }

    @Test
    fun `retries on 429 Too Many Requests`() =
        runTest {
            val attempts = AtomicInteger(0)
            val engine =
                MockEngine { _ ->
                    val n = attempts.incrementAndGet()
                    if (n < 2) respondError(HttpStatusCode.TooManyRequests) else respond("done")
                }
            val client =
                RpcHttpClient.create(
                    engine,
                    RpcHttpClient.Config(maxRetries = 3, maxBackoffMillis = 10, randomJitterMillis = 0),
                )
            assertEquals("done", client.get("http://test/x").bodyAsText())
            assertEquals(2, attempts.get())
        }

    @Test
    fun `retries on transport IOException`() =
        runTest {
            val attempts = AtomicInteger(0)
            val engine =
                MockEngine { _ ->
                    val n = attempts.incrementAndGet()
                    if (n < 2) throw IOException("simulated transport failure") else respond("ok")
                }
            val client =
                RpcHttpClient.create(
                    engine,
                    RpcHttpClient.Config(maxRetries = 3, maxBackoffMillis = 10, randomJitterMillis = 0),
                )
            assertEquals("ok", client.get("http://test/x").bodyAsText())
            assertEquals(2, attempts.get())
        }

    @Test
    fun `does not retry on 4xx other than 429`() =
        runTest {
            val attempts = AtomicInteger(0)
            val engine =
                MockEngine { _ ->
                    attempts.incrementAndGet()
                    respondError(HttpStatusCode.BadRequest)
                }
            val client =
                RpcHttpClient.create(
                    engine,
                    RpcHttpClient.Config(maxRetries = 3, maxBackoffMillis = 10, randomJitterMillis = 0),
                )
            val response = client.get("http://test/x")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(1, attempts.get())
        }

    @Test
    fun `request timeout fires when server stalls`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    delay(1_000)
                    respond(
                        content = ByteReadChannel("late"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(),
                    )
                }
            val client =
                RpcHttpClient.create(
                    engine,
                    RpcHttpClient.Config(
                        requestTimeoutMillis = 50,
                        maxRetries = 0,
                        maxBackoffMillis = 10,
                        randomJitterMillis = 0,
                    ),
                )
            val ex =
                assertFailsWith<HttpRequestTimeoutException> {
                    client.get("http://test/x").bodyAsText()
                }
            assertTrue(ex.message!!.contains("timeout", ignoreCase = true))
        }
}
