// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectPixResolverTest {
    @Test
    fun `fetches issuer directly and authenticates its JWS through same-host JWKS`() =
        runTest {
            val captured = mutableListOf<Url>()
            var verifiedAlgorithm: String? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        captured += request.url
                        when (request.url.encodedPath) {
                            "/v2/cobv/abc" -> respond(PixJwsTestFixtures.token())
                            "/.well-known/pix-jwks.json" -> respond(PixJwsTestFixtures.jwks())
                            else -> error("unexpected request ${request.url}")
                        }
                    }
                )
            val verifier =
                PixJwsVerifier(client) { algorithm, input, signature, certificates ->
                    verifiedAlgorithm = algorithm
                    assertTrue(input.decodeToString().contains('.'))
                    assertEquals(byteArrayOf(1, 2, 3).toList(), signature.toList())
                    assertEquals(PixJwsTestFixtures.certificate.toList(), certificates.single().toList())
                    true
                }

            val amount =
                DirectPixResolver
                    .withVerifier(client, verifier)
                    .resolveAmount(PixJwsTestFixtures.ISSUER_URL, "order-1")

            assertEquals("123.45", amount)
            assertEquals(listOf("/v2/cobv/abc", "/.well-known/pix-jwks.json"), captured.map(Url::encodedPath))
            assertEquals("RS256", verifiedAlgorithm)
        }

    @Test
    fun `untrusted certificate or invalid signature fails closed`() =
        runTest {
            val client = pixClient(PixJwsTestFixtures.token(), PixJwsTestFixtures.jwks())
            val verifier = PixJwsVerifier(client) { _, _, _, _ -> false }

            assertFailsWith<PixFetchException> {
                DirectPixResolver.withVerifier(client, verifier).resolveAmount(PixJwsTestFixtures.ISSUER_URL, null)
            }
        }

    @Test
    fun `rejects cross-host JKU before requesting it`() =
        runTest {
            var requests = 0
            val token = PixJwsTestFixtures.token(jku = "https://attacker.test/jwks")
            val client =
                HttpClient(
                    MockEngine {
                        requests += 1
                        respond(token)
                    }
                )
            val verifier = PixJwsVerifier(client) { _, _, _, _ -> true }

            assertFailsWith<PixFetchException> {
                DirectPixResolver.withVerifier(client, verifier).resolveAmount(PixJwsTestFixtures.ISSUER_URL, null)
            }
            assertEquals(1, requests)
        }

    @Test
    fun `rejects certificate thumbprint mismatch`() =
        runTest {
            val client = pixClient(PixJwsTestFixtures.token(thumbprint = "wrong"), PixJwsTestFixtures.jwks())
            val verifier = PixJwsVerifier(client) { _, _, _, _ -> true }

            assertFailsWith<PixFetchException> {
                DirectPixResolver.withVerifier(client, verifier).resolveAmount(PixJwsTestFixtures.ISSUER_URL, null)
            }
        }

    @Test
    fun `rejects missing JWKS thumbprint and unsupported critical JWS headers`() =
        runTest {
            val cases =
                listOf(
                    PixJwsTestFixtures.token() to PixJwsTestFixtures.jwks(thumbprintField = ""),
                    PixJwsTestFixtures.token(extraHeader = ",\"crit\":[\"b64\"],\"b64\":false") to
                        PixJwsTestFixtures.jwks(),
                )
            cases.forEach { (token, jwks) ->
                val client = pixClient(token, jwks)
                val verifier = PixJwsVerifier(client) { _, _, _, _ -> true }
                assertFailsWith<PixFetchException> {
                    DirectPixResolver.withVerifier(client, verifier).resolveAmount(PixJwsTestFixtures.ISSUER_URL, null)
                }
            }
        }

    @Test
    fun `rejects ambiguous key id and key without verify operation`() =
        runTest {
            listOf(
                PixJwsTestFixtures.jwks(keys = 2),
                PixJwsTestFixtures.jwks(keyOperations = ""),
            ).forEach { jwks ->
                val client = pixClient(PixJwsTestFixtures.token(), jwks)
                val verifier = PixJwsVerifier(client) { _, _, _, _ -> true }
                assertFailsWith<PixFetchException> {
                    DirectPixResolver.withVerifier(client, verifier).resolveAmount(PixJwsTestFixtures.ISSUER_URL, null)
                }
            }
        }

    @Test
    fun `JWT without valor returns null only after successful verification`() =
        runTest {
            val client = pixClient(PixJwsTestFixtures.token(payload = """{"txid":"x"}"""), PixJwsTestFixtures.jwks())
            val verifier = PixJwsVerifier(client) { _, _, _, _ -> true }
            assertNull(
                DirectPixResolver.withVerifier(client, verifier).resolveAmount(PixJwsTestFixtures.ISSUER_URL, null)
            )
        }

    @Test
    fun `non-2xx and malformed JWS throw`() =
        runTest {
            val notFound = DirectPixResolver(HttpClient(MockEngine { respond("nope", HttpStatusCode.NotFound) }))
            assertFailsWith<PixFetchException> { notFound.resolveAmount(PixJwsTestFixtures.ISSUER_URL, null) }

            val malformed = DirectPixResolver(HttpClient(MockEngine { respond("not-a-jws") }))
            assertFailsWith<PixFetchException> { malformed.resolveAmount(PixJwsTestFixtures.ISSUER_URL, null) }
        }

    @Test
    fun `rejects unsafe locations before networking`() =
        runTest {
            val resolver = DirectPixResolver(HttpClient(MockEngine { error("network must not be reached") }))
            listOf(
                "http://bank.test/x",
                "https://localhost/x",
                "https://127.0.0.1/x",
                "https://10.0.0.1/x",
                "https://169.254.169.254/latest/meta-data",
                "https://[::1]/x",
                "https://bank.test:8443/x",
            ).forEach { location ->
                assertFailsWith<PixFetchException>(location) { resolver.resolveAmount(location, null) }
            }
        }

    @Test
    fun `rejects an oversized bank response`() =
        runTest {
            val oversized = "a".repeat(64 * 1024 + 1)
            val resolver = DirectPixResolver(HttpClient(MockEngine { respond(oversized) }))

            assertFailsWith<PixFetchException> { resolver.resolveAmount(PixJwsTestFixtures.ISSUER_URL, null) }
        }

    private fun pixClient(token: String, jwks: String): HttpClient =
        HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/v2/cobv/abc" -> respond(token)
                    "/.well-known/pix-jwks.json" -> respond(jwks)
                    else -> error("unexpected request ${request.url}")
                }
            }
        )
}
