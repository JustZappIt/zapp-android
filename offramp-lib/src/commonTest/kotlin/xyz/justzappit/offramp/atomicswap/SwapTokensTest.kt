// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwapTokensTest {
    @Test
    fun anAcceptAskedForATokenGoesOnceMoreWithOneThatSurvivesARestartAndIsNeverSentTwice() =
        runTest {
            val store = Store()
            val issuer = Issuer(left = 2)
            val maker = Maker(PINNED_ISSUER)

            assertEquals(ACCEPTED, maker.client(tokens(store, issuer)).accept(QUOTE_ID, ACCEPTANCE))
            assertEquals(1, issuer.batches, "a short batch: the issuer had two of three left")
            assertEquals(1, store.state.held.size)

            assertEquals(ACCEPTED, maker.client(tokens(store, issuer)).accept(QUOTE_ID, ACCEPTANCE))
            assertEquals(1, issuer.batches, "the token kept across the restart pays")
            val spent = maker.authorizations.filterNotNull()
            assertEquals(listOf(null, spent[0], null, spent[1]), maker.authorizations)
            assertEquals(2, spent.toSet().size)

            val tomorrow = maker.client(tokens(store, issuer))
            val exhausted = assertFailsWith<AtomicSwapBlockedException> { tomorrow.accept(QUOTE_ID, ACCEPTANCE) }
            assertEquals(AtomicSwapBlock.TOKENS_EXHAUSTED, exhausted.reason)
            assertEquals(1, issuer.batches, "a spent day isn't asked again")
        }

    @Test
    fun aChallengeForAnotherIssuerFetchesNothingAndASpentDaySaysSo() =
        runTest {
            val issuer = Issuer(left = 0)

            val elsewhere = Maker("elsewhere").client(tokens(Store(), issuer))
            val refused = assertFailsWith<AtomicSwapBlockedException> { elsewhere.accept(QUOTE_ID, ACCEPTANCE) }
            assertEquals(AtomicSwapBlock.TOKENS_REFUSED, refused.reason)
            assertEquals(0, issuer.requests)

            val store = Store()
            val spent = Maker(PINNED_ISSUER).client(tokens(store, issuer))
            val exhausted = assertFailsWith<AtomicSwapBlockedException> { spent.accept(QUOTE_ID, ACCEPTANCE) }
            assertEquals(AtomicSwapBlock.TOKENS_EXHAUSTED, exhausted.reason)
            assertTrue(store.state.held.isEmpty())
        }

    private fun tokens(
        store: Store,
        issuer: Issuer
    ) = SwapTokens(ISSUER, { HttpClient(issuer.engine) }, Crypto(), { ByteArray(32) { 1 } }, store, { NOW })

    private class Store : SwapTokenStore {
        var state = SwapTokenState()

        override suspend fun load() = state

        override suspend fun save(state: SwapTokenState) {
            this.state = state
        }
    }

    /** Signs what the device's day has left of each batch, the first requests in order, as zecSwap's issuer does. */
    private class Issuer(
        var left: Int,
    ) {
        var requests = 0
        var batches = 0
        val engine =
            MockEngine { request ->
                requests++
                when (request.url.encodedPath) {
                    "/issuer/v1/token-key" -> {
                        respond(
                            """{"issuer":"$PINNED_ISSUER","tokenKey":"$PINNED_KEY","tokensPerDay":3}""",
                            headers = JSON_HEADERS,
                        )
                    }

                    else -> {
                        val blinded =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject
                                .getValue("blinded")
                                .jsonArray
                                .map { it.jsonPrimitive.content }
                        if (left == 0) {
                            respond("", HttpStatusCode.TooManyRequests)
                        } else {
                            batches++
                            val signed = blinded.take(left).also { left -= it.size }
                            val signatures = signed.joinToString { "\"$it\"" }
                            respond("""{"blindSignatures":[$signatures]}""", headers = JSON_HEADERS)
                        }
                    }
                }
            }
    }

    /** Asks for a token under [issuer]'s name until a request carries one. */
    private class Maker(
        private val issuer: String,
    ) {
        val authorizations = mutableListOf<String?>()
        private val http =
            HttpClient(
                MockEngine { request ->
                    val authorization = request.headers[HttpHeaders.Authorization]
                    authorizations += authorization
                    if (authorization == null) {
                        respond(
                            """{"code":"tokenRequired","error":"this request takes a token"}""",
                            HttpStatusCode.Unauthorized,
                            headersOf(
                                HttpHeaders.WWWAuthenticate,
                                "PrivateToken challenge=\"${base64Url.encode(issuer.encodeToByteArray())}\", " +
                                    "token-key=\"$PINNED_KEY\"",
                            ),
                        )
                    } else {
                        respond(
                            """{"swapId":"${ACCEPTED.swapId}","t0":${ACCEPTED.t0},"t1":${ACCEPTED.t1}}""",
                            headers = JSON_HEADERS,
                        )
                    }
                }
            )

        fun client(tokens: SwapTokenSource) = MakerClient(http, Url("https://maker"), tokens)
    }

    /** RFC 9578's math is zecSwap's to test: here a challenge names its issuer, and a token is its request signed. */
    private class Crypto : PrivacyPassClient {
        private var next: Byte = 0

        override fun challenge(header: String): TokenChallenge {
            val (challenge, key) = Regex("\"([^\"]*)\"").findAll(header).map { it.groupValues[1] }.toList()
            val bytes = base64Url.decode(challenge)
            return TokenChallenge(bytes, bytes.decodeToString(), base64Url.decode(key))
        }

        override fun blind(
            challenge: ByteArray,
            tokenKey: ByteArray
        ) = ByteArray(BLINDED) { next }.let { BlindedToken(it, it).also { next++ } }

        override fun finalize(
            pending: ByteArray,
            tokenKey: ByteArray,
            blindSignature: ByteArray
        ): ByteArray {
            check(pending.contentEquals(blindSignature)) { "a signature for another request" }
            return pending
        }

        override fun authorization(token: ByteArray) = "PrivateToken token=\"${base64Url.encode(token)}\""
    }

    private companion object {
        const val NOW = 1_790_000_000L
        const val BLINDED = 256
        const val PINNED_ISSUER = "issuer.test"
        const val QUOTE_ID = "0x2222222222222222222222222222222222222222222222222222222222222222"
        val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val PINNED_KEY = base64Url.encode("a fake token key".encodeToByteArray())
        val ISSUER =
            SwapTokenIssuer(Url("https://tokens/issuer"), PINNED_ISSUER, PINNED_KEY, TokenAttestation.INSECURE_TEST)
        val ACCEPTED = SwapAccepted(SwapId.of(ByteArray(32) { 3 }), 1_790_003_600, 1_790_007_200)
        val ACCEPTANCE = SwapAcceptance("0x" + "0c".repeat(64), "0x" + "04".repeat(64), "0x" + "05".repeat(64))
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
