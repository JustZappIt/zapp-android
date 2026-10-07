// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SwapTokensTest {
    @Test
    fun aRefusedAcceptsTokenPaysForTheNextAndOneTheMakerKeptUnansweredGivesWayToTheNext() =
        runTest {
            val device = Device()
            val maker = Maker(device)

            maker.refused += quote(1)
            assertFailsWith<AtomicSwapHttpException.Refused> { maker.client().accept(quote(1), swap(1), ACCEPTANCE) }
            maker.client().accept(quote(2), swap(2), ACCEPTANCE)
            maker.losesAnswer = true
            val lost = maker.client()
            assertFailsWith<AtomicSwapHttpException.Unreachable> { lost.accept(quote(3), swap(3), ACCEPTANCE) }
            maker.client().accept(quote(4), swap(4), ACCEPTANCE)

            val sent = maker.paid.map { it.token }
            assertEquals(sent[0], sent[1], "the refused accept's token paid for the next")
            assertEquals(sent[2], sent[3], "the token whose answer was lost went first")
            assertNotEquals(sent[3], sent[4], "the maker refused it, and the next one paid")
            assertEquals(listOf(400, 200, 200, 401, 200), maker.paid.map { it.status })
            assertEquals(1, device.issuer.batches)
            assertTrue(maker.paid.all { it.request != null } && maker.unpaid.none { "tokenRequest" in it })
            val asked = device.state.returns.map { it.swapId }
            assertEquals((1..4).map { swap(it) }, asked, "the request sent with the token the maker refused is dropped")
        }

    @Test
    fun aTokenHandedBackIsSpentOnlyOnceTheIssuersAreGoneAndAtOnceThen() =
        runTest {
            val device = Device(left = 2)
            val maker = Maker(device)
            val client = maker.client()

            client.acceptReverse(quote(1), swap(1), ACCEPTANCE)
            client.collectReverseToken(swap(1))
            maker.paidIn += swap(1)
            client.collectReverseToken(swap(1))
            client.collectReverseToken(swap(1))
            assertEquals(2, maker.statusReads, "read until the token came back, and not after")
            val returned = device.state.held.single { it.tokenKey == RETURN_KEY }
            assertEquals(maker.paid[0].request, returned.token, "the maker's signature finalized the accept's request")

            client.accept(quote(2), swap(2), ACCEPTANCE)
            client.accept(quote(3), swap(3), ACCEPTANCE)
            assertEquals(listOf(ISSUER_KEY, ISSUER_KEY, RETURN_KEY), maker.paid.map { it.key }, "the issuer's go first")
            assertEquals(returned.token, maker.paid[2].token)
            val none = assertFailsWith<AtomicSwapBlockedException> { client.accept(quote(4), swap(4), ACCEPTANCE) }
            assertEquals(AtomicSwapBlock.TOKENS_EXHAUSTED, none.reason)

            maker.paidIn += swap(2)
            client.collectToken(swap(2))
            client.accept(quote(4), swap(4), ACCEPTANCE)
            assertEquals(RETURN_KEY, maker.paid.last().key, "a token handed back pays as soon as it's held")
            assertEquals(1, device.issuer.batches)
        }

    @Test
    fun nothingFromAnEarlierDayIsSentAndOnlyTodaysChallengeIsTaken() =
        runTest {
            val device = Device(left = 7)
            device.now = NOW - SECONDS_PER_DAY
            val maker = Maker(device)
            val client = maker.client()
            client.accept(quote(1), swap(1), ACCEPTANCE)
            maker.paidIn += swap(1)
            client.collectToken(swap(1))
            maker.refused += quote(2)
            assertFailsWith<AtomicSwapHttpException.Refused> { client.accept(quote(2), swap(2), ACCEPTANCE) }
            client.accept(quote(3), swap(3), ACCEPTANCE)
            maker.paidIn += swap(3)

            device.now = NOW
            client.collectToken(swap(3))
            device.tokens().prefetch()
            assertTrue(device.state.run { held.all { it.day == TODAY } && returns.isEmpty() })
            client.accept(quote(4), swap(4), ACCEPTANCE)
            assertEquals(listOf(200, 400, 200, 200), maker.paid.map { it.status }, "no token of yesterday's was sent")
            assertEquals(TODAY, maker.paid.last().day)
            assertEquals(1, maker.statusReads, "yesterday's request for its token back was dropped unread")
            assertEquals(2, device.issuer.batches)

            maker.day = TODAY + 2
            val tomorrow = assertFailsWith<AtomicSwapBlockedException> { client.accept(quote(5), swap(5), ACCEPTANCE) }
            maker.day = null
            maker.issuer = "elsewhere"
            val elsewhere = assertFailsWith<AtomicSwapBlockedException> { client.accept(quote(5), swap(5), ACCEPTANCE) }
            assertEquals(AtomicSwapBlock.TOKENS_REFUSED, tomorrow.reason)
            assertEquals(AtomicSwapBlock.TOKENS_REFUSED, elsewhere.reason)
            assertEquals(4, maker.paid.size)

            maker.issuer = PINNED_ISSUER
            maker.day = TODAY + 1
            device.now = (TODAY + 1) * SECONDS_PER_DAY - 5 * 60
            client.accept(quote(6), swap(6), ACCEPTANCE)
            assertEquals(TODAY + 1, maker.paid.last().day, "the maker's day turned minutes before ours")
        }

    @Test
    fun aMakerThatTakesNoTokensIsAcceptedWithoutOneOrARequestForOne() =
        runTest {
            val device = Device()
            val maker = Maker(device, takesTokens = false)

            maker.client().acceptReverse(quote(1), swap(1), ACCEPTANCE)
            maker.client().accept(quote(2), swap(2), ACCEPTANCE)

            assertEquals(2, maker.unpaid.size)
            assertTrue(maker.unpaid.none { "tokenRequest" in it } && maker.paid.isEmpty())
            assertEquals(0, device.issuer.requests)
        }

    /** One install: its clock, its kept tokens, and the issuer's allowance for it. */
    private class Device(
        left: Int = 3,
    ) {
        var now = NOW
        val issuer = Issuer(left)
        private val store = Store()
        private val crypto = Crypto()

        val state: SwapTokenState get() = store.state

        fun tokens() =
            SwapTokens(ISSUER, { HttpClient(issuer.engine) }, crypto, { ByteArray(32) { 1 } }, store, { now })
    }

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
                            """{"issuer":"$PINNED_ISSUER","tokenKey":"$ISSUER_KEY","tokensPerDay":3}""",
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

    /**
     * zecSwap's maker, with `[tokens]` unless not [takesTokens]: it asks an accept for a token on its day, keeps the
     * token only once it takes the quote, wants a request for it back, and signs that request once the swap is paid in.
     */
    private class Maker(
        private val device: Device,
        private val takesTokens: Boolean = true,
    ) {
        val refused = mutableSetOf<String>()
        val paidIn = mutableSetOf<SwapId>()
        var losesAnswer = false
        var day: Long? = null
        var issuer = PINNED_ISSUER
        val paid = mutableListOf<Paid>()
        val unpaid = mutableListOf<String>()
        var statusReads = 0
        private val spent = mutableSetOf<String>()
        private val requests = mutableMapOf<String, String>()
        private val http =
            HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    if (path.endsWith("/accept")) {
                        val body = (request.body as TextContent).text
                        val token = request.headers[HttpHeaders.Authorization]?.substringAfter('"')?.removeSuffix("\"")
                        accept(path.removeSuffix("/accept").substringAfterLast('/'), body, token)
                    } else {
                        statusReads++
                        val id = path.substringAfterLast('/')
                        val signed = requests[id]?.takeIf { SwapId.parse(id) in paidIn }
                        val tokenReturn = signed?.let { "\"$it\"" }
                        respond("""{"swapId":"$id","tokenReturn":$tokenReturn}""", headers = JSON_HEADERS)
                    }
                }
            )

        fun client() = MakerClient(http, Url("https://maker"), device.tokens())

        private fun MockRequestHandleScope.accept(
            quote: String,
            body: String,
            token: String?,
        ): HttpResponseData {
            if (token == null) unpaid += body
            val fields = Json.parseToJsonElement(body).jsonObject
            val request = fields["tokenRequest"]?.jsonPrimitive?.content
            val status = answer(quote, request, token)
            if (token != null) paid += Paid(token, request, status.value)
            return when (status) {
                HttpStatusCode.Unauthorized -> {
                    val asked = "PrivateToken challenge=\"${base64Url.encode(challenge())}\", token-key=\"$ISSUER_KEY\""
                    respond("""{"code":"tokenRequired"}""", status, headersOf(HttpHeaders.WWWAuthenticate, asked))
                }

                HttpStatusCode.BadRequest -> {
                    respond("""{"code":"rejected","error":"refused"}""", status)
                }

                else -> {
                    token?.let { spent += it }
                    request?.let { requests[quote] = it }
                    if (losesAnswer) {
                        losesAnswer = false
                        throw IOException("the answer was lost")
                    }
                    respond("""{"swapId":"$quote","t0":1790003600,"t1":1790007200}""", headers = JSON_HEADERS)
                }
            }
        }

        // A token is held only if it's good on the maker's day and unspent; an accept refused before the quote is
        // taken, for a refused quote or a request for the token back that's missing or not wanted, leaves it unspent.
        private fun answer(
            quote: String,
            request: String?,
            token: String?,
        ): HttpStatusCode {
            val isHeld =
                token != null &&
                    token !in spent &&
                    token.parts()[1] in setOf(ISSUER_KEY, RETURN_KEY) &&
                    token.challenge().contentEquals(challenge())
            return when {
                takesTokens && !isHeld -> HttpStatusCode.Unauthorized
                quote in refused || takesTokens != (request != null) -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.OK
            }
        }

        private fun challenge() = challenge(issuer, day ?: (device.now / SECONDS_PER_DAY))
    }

    /** A paid accept as the maker saw it: the token, base64url, the request for one back, and the answer. */
    private class Paid(
        val token: String,
        val request: String?,
        val status: Int,
    ) {
        val key: String get() = token.parts()[1]
        val day: Long get() = dayOf(token.challenge())
    }

    /** RFC 9578's math is zecSwap's to test: here a request names its key and challenge, and signing leaves it be. */
    private class Crypto : PrivacyPassClient {
        private var next = 0

        override fun challenge(header: String): TokenChallenge {
            val (challenge, key) = Regex("\"([^\"]*)\"").findAll(header).map { it.groupValues[1] }.toList()
            val bytes = base64Url.decode(challenge)
            return TokenChallenge(bytes, bytes.copyOfRange(4, 4 + bytes[3]).decodeToString(), base64Url.decode(key))
        }

        override fun blind(
            challenge: ByteArray,
            tokenKey: ByteArray
        ): BlindedToken {
            val request = "${next++}|${base64Url.encode(tokenKey)}|${base64Url.encode(challenge)}".encodeToByteArray()
            return BlindedToken(request, request)
        }

        override fun finalize(
            pending: ByteArray,
            tokenKey: ByteArray,
            blindSignature: ByteArray
        ): ByteArray {
            val key = pending.decodeToString().split('|')[1]
            return pending.takeIf { it.contentEquals(blindSignature) && key == base64Url.encode(tokenKey) } ?: refuse()
        }

        // As libzecswap's refusal reaches the app.
        private fun refuse(): Nothing =
            throw AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_REFUSED, "a signature for another request")

        override fun authorization(token: ByteArray) = "PrivateToken token=\"${base64Url.encode(token)}\""
    }

    private companion object {
        const val NOW = 1_790_000_000L
        const val SECONDS_PER_DAY = 86_400L
        const val TODAY = NOW / SECONDS_PER_DAY
        const val PINNED_ISSUER = "issuer.test"
        val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val ISSUER_KEY = base64Url.encode("a fake token key".encodeToByteArray())
        val RETURN_KEY = base64Url.encode("a fake return key".encodeToByteArray())
        val ISSUER =
            SwapTokenIssuer(
                Url("https://tokens/issuer"),
                PINNED_ISSUER,
                ISSUER_KEY,
                TokenAttestation.INSECURE_TEST,
                RETURN_KEY,
            )
        val ACCEPTANCE = SwapAcceptance("0x" + "0c".repeat(64), "0x" + "04".repeat(64), "0x" + "05".repeat(64))
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

        fun swap(n: Int) = SwapId.of(ByteArray(32) { n.toByte() })

        fun quote(n: Int) = swap(n).hex

        val HeldToken.day get() = dayOf(base64Url.decode(challenge))

        fun String.parts() = base64Url.decode(this).decodeToString().split('|')

        fun String.challenge() = base64Url.decode(parts()[2])

        /** RFC 9577's challenge, dated as zecSwap's makers date theirs. */
        fun challenge(
            issuer: String,
            day: Long
        ): ByteArray {
            val name = issuer.encodeToByteArray()
            val dated = ByteArray(24) + ByteArray(8) { (day ushr 8 * (7 - it)).toByte() }
            val origin = byteArrayOf(0, 5) + "maker".encodeToByteArray()
            return byteArrayOf(0, 2, 0, name.size.toByte()) + name + byteArrayOf(32) + dated + origin
        }

        fun dayOf(challenge: ByteArray): Long {
            val at = 5 + challenge[3] + 24
            return challenge.copyOfRange(at, at + 8).fold(0L) { day, byte -> day shl 8 or (byte.toLong() and 0xff) }
        }
    }
}
