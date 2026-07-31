// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixProxyClientTest {
    @Test
    fun `compatibility proxy still authenticates the issuer JWS`() =
        runTest {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request.url
                        when (request.url.encodedPath) {
                            "/pix" -> respond(PixJwsTestFixtures.token())
                            "/.well-known/pix-jwks.json" -> respond(PixJwsTestFixtures.jwks())
                            else -> error("unexpected request ${request.url}")
                        }
                    }
                )

            val result =
                runCatching {
                    PixProxyClient(client, "https://proxy.test")
                        .resolveAmount(PixJwsTestFixtures.ISSUER_URL, "order-1")
                }

            assertTrue(result.isFailure)
            assertEquals(listOf("proxy.test", "bank.test"), requests.map(Url::host))
            assertEquals("https://bank.test/v2/cobv/abc", requests.first().parameters["locationUrl"])
        }
}
