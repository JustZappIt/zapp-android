// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the invariant [CustodialOnrampClient.markPaid] states: a paid assertion releases a
 * merchant's USDC, so it must reach the service at most once per user action.
 *
 * The client is built with the production [RpcHttpClient] defaults rather than a bare engine,
 * because the bug this guards against lived in those defaults — `HttpRequestRetry` re-sent every
 * 5xx and every socket timeout, including on POST, and a socket timeout is exactly the case where
 * the service has already acted.
 */
class OnrampPostRetryTest {
    @Test
    fun `a paid assertion is sent once even when the service answers 5xx`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val client = clientOver(seen, SERVER_ERROR, HttpStatusCode.InternalServerError)

            runCatching { client.markPaid(ORDER_UUID) }

            assertEquals(1, seen.count { it.url.encodedPath.endsWith("/paid") }, "paid must not be auto-retried")
        }

    @Test
    fun `placing an order is sent once even when the service answers 5xx`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val client = clientOver(seen, SERVER_ERROR, HttpStatusCode.InternalServerError)

            runCatching { client.createOrder("q", signer.address, DEVICE) }

            assertEquals(1, seen.count { it.url.encodedPath.endsWith("/v1/orders") }, "orders must not be auto-retried")
        }

    /**
     * The one deliberate re-send. It fires only on `NONCE_INVALID`, which the service returns
     * before doing anything, so it cannot double-assert.
     */
    @Test
    fun `a rejected nonce still earns exactly one fresh attempt`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val client = clientOver(seen, NONCE_REJECTED, HttpStatusCode.Unauthorized)

            runCatching { client.markPaid(ORDER_UUID) }

            assertEquals(2, seen.count { it.url.encodedPath.endsWith("/paid") })
        }

    private fun clientOver(
        record: MutableList<HttpRequestData>,
        postBody: String,
        postStatus: HttpStatusCode,
    ): CustodialOnrampClient {
        val engine =
            MockEngine { request ->
                record.add(request)
                if (request.url.encodedPath.endsWith("/v1/config")) {
                    respond(CONFIG_BODY, HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(postBody, postStatus, jsonHeaders)
                }
            }
        return CustodialOnrampClient(
            // The production defaults, not a bare engine: the retry that had to be disabled is theirs.
            httpClient = RpcHttpClient.create(engine),
            baseUrl = "https://onramp.example",
            signerProvider = { signer },
        )
    }

    private companion object {
        const val ORDER_UUID = "00000000-0000-4000-8000-000000000000"
        const val NONCE = "11111111-1111-4111-8111-111111111111"
        const val SERVER_ERROR = """{"code":"UPSTREAM_FAILED","message":"boom"}"""
        const val NONCE_REJECTED = """{"code":"NONCE_INVALID","message":"spent"}"""
        const val CONFIG_BODY =
            """{"nonce":"$NONCE","enabled":true,"currency":"INR","minFiat":"104260000",""" +
                """"maxFiat":"2085200000","perUserDailyFiat":"5213000000","chainId":8453}"""

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        val signer =
            OnrampRequestSigner(
                EvmKeyDerivation.fromPrivateKey(
                    "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318".hexToBytes(),
                ),
            )

        val DEVICE =
            OnrampDeviceSignals(
                userAgent = "test",
                platform = "Android",
                language = "en-IN",
                languages = listOf("en-IN"),
                screenWidth = 1080,
                screenHeight = 2400,
                devicePixelRatio = 2.75,
                timezone = "Asia/Kolkata",
                timezoneOffset = -330,
                cookiesEnabled = true,
                doNotTrack = null,
                online = true,
                touchSupport = true,
                maxTouchPoints = 5,
                vendor = "Test",
                appVersion = "1.0.0",
                colorDepth = 24,
                pixelDepth = 24,
                connectionType = "wifi",
                deviceMemory = 8.0,
                hardwareConcurrency = 8,
            )
    }
}
