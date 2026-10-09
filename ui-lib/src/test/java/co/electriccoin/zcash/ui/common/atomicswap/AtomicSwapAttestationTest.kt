// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.BlindedToken
import xyz.justzappit.offramp.atomicswap.KeyAttestation
import xyz.justzappit.offramp.atomicswap.PrivacyPassClient
import xyz.justzappit.offramp.atomicswap.SwapTokenIssuer
import xyz.justzappit.offramp.atomicswap.SwapTokenState
import xyz.justzappit.offramp.atomicswap.SwapTokenStore
import xyz.justzappit.offramp.atomicswap.SwapTokens
import xyz.justzappit.offramp.atomicswap.TokenChallenge
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AtomicSwapAttestationTest {
    @Test
    fun `refusals and a spent day leave the install's one key as it was, and each request signs its own challenge`() =
        runTest {
            val keys = Keys()
            val issuer = Issuer()
            val install = Install(keys, issuer)

            install.tokens.prefetch()
            assertEquals(1, keys.made, "made ahead, before any maker asked for a token")
            assertEquals(KEY_CHALLENGE, keys.challenges.single().toHex())
            assertTrue(issuer.log.isEmpty(), "nothing asked of the issuer")

            install.askedFor()
            issuer.answer = HttpStatusCode.Forbidden
            val refused = assertFailsWith<AtomicSwapBlockedException> { install.tokens.prefetch() }
            assertEquals(AtomicSwapBlock.TOKENS_REFUSED, refused.reason)
            assertTrue("bootloader unlocked" in refused.message.orEmpty(), "the issuer's reason")
            issuer.answer = HttpStatusCode.TooManyRequests
            install.tokens.prefetch()
            assertEquals(TODAY, install.store.state.exhaustedDay)
            install.askedFor()
            issuer.answer = HttpStatusCode.OK
            install.tokens.prefetch()
            assertEquals(3, install.store.state.held.size)

            assertEquals(1, keys.made, "neither a refusal nor a spent day makes another key")
            assertEquals(List(3) { listOf("token-key", "challenge", "tokens") }.flatten(), issuer.log)
            val posts = issuer.posts.map(::Post)
            assertEquals(issuer.challenges, posts.map { it.challenge }, "each request, the challenge given just before")
            assertEquals(3, issuer.challenges.toSet().size)
            assertTrue(posts.all { it.chain == keys.encodedChain() })
            posts.forEach { it.assertSignedBy(keys.key) }
        }

    @Test
    fun `a key whose chain has expired is made again, and its new chain sent`() =
        runTest {
            val keys = Keys()
            val issuer = Issuer()
            val install = Install(keys, issuer)
            install.askedFor()
            install.tokens.prefetch()
            val first = keys.encodedChain()

            keys.expire()
            install.askedFor()
            install.tokens.prefetch()

            assertEquals(2, keys.made)
            val (before, after) = issuer.posts.map { Post(it).chain }
            assertEquals(first, before)
            assertEquals(keys.encodedChain(), after)
            assertNotEquals(before, after)
            Post(issuer.posts.last()).assertSignedBy(keys.key)
        }

    /** An install's tokens as the app keeps them, from [issuer], with [keys] for the phone's secure hardware. */
    private class Install(
        keys: Keys,
        issuer: Issuer,
    ) {
        val store = Store()
        val tokens =
            SwapTokens(
                ISSUER,
                { HttpClient(issuer.engine) },
                Blinding,
                AtomicSwapAttestation(ISSUER.name, keys) { NOW_MILLIS },
                store,
            ) { NOW_SECONDS }

        /** A maker asked for today's tokens, and none is held. */
        fun askedFor() {
            store.state = SwapTokenState(challenge = MAKER_CHALLENGE)
        }
    }

    private class Store : SwapTokenStore {
        var state = SwapTokenState()

        override suspend fun load() = state

        override suspend fun save(state: SwapTokenState) {
            this.state = state
        }
    }

    /** A P-256 key for each [generate], whose chain's batch certificate runs out when [expire] says. */
    private class Keys : AttestationKeys {
        var made = 0
        val challenges = mutableListOf<ByteArray>()
        private val entries = mutableMapOf<String, Pair<KeyPair, List<X509Certificate>>>()

        private val entry get() = entries.values.single()

        val key: KeyPair get() = entry.first

        fun encodedChain() = entry.second.map { base64Url.encode(it.encoded) }

        fun expire() {
            val (alias, entry) = entries.entries.single()
            val (key, chain) = entry
            entries[alias] = key to listOf(chain[0], certificate("batch $made", NOW_MILLIS - 1))
        }

        override fun chain(alias: String) = entries[alias]?.second

        override fun generate(
            alias: String,
            challenge: ByteArray
        ) {
            made++
            challenges += challenge
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val chain = listOf("leaf $made", "batch $made").map { certificate(it, NOW_MILLIS + YEAR) }
            entries[alias] = generator.generateKeyPair() to chain
        }

        override fun sign(
            alias: String,
            message: ByteArray
        ): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(entries.getValue(alias).first.private)
                update(message)
                sign()
            }

        private fun certificate(
            der: String,
            notAfter: Long
        ) = mockk<X509Certificate> {
            every { encoded } returns der.encodeToByteArray()
            every { this@mockk.notAfter } returns Date(notAfter)
        }
    }

    /** zecSwap's issuer: a `POST /v1/tokens` gets [answer], and a 200 signs every request in it. */
    private class Issuer {
        var answer = HttpStatusCode.OK
        val log = mutableListOf<String>()
        val challenges = mutableListOf<String>()
        val posts = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                val route = request.url.encodedPath.removePrefix("/issuer/v1/")
                log += route
                when (route) {
                    "token-key" -> {
                        respond("""{"issuer":"$NAME","tokenKey":"$TOKEN_KEY","tokensPerDay":3}""", headers = JSON)
                    }

                    "challenge" -> {
                        val challenge = base64Url.encode(Random.nextBytes(CHALLENGE_BYTES))
                        challenges += challenge
                        respond("""{"challenge":"$challenge"}""", headers = JSON)
                    }

                    else -> {
                        val body = (request.body as TextContent).text
                        posts += body
                        if (answer == HttpStatusCode.OK) {
                            val blinded = Json.parseToJsonElement(body).jsonObject.getValue("blinded")
                            respond("""{"blindSignatures":$blinded}""", headers = JSON)
                        } else {
                            respond(REFUSALS.getValue(answer), answer, JSON)
                        }
                    }
                }
            }
    }

    /** A `POST /v1/tokens` body read strictly: zecSwap's fields and no others, each base64url without padding. */
    private class Post(
        body: String
    ) {
        private val fields = Json.parseToJsonElement(body).jsonObject
        private val attestation = fields.getValue("attestation").jsonObject
        val challenge = attestation.text("challenge")
        val chain = attestation.texts("chain")
        val signature = attestation.text("signature")
        val blinded = fields.texts("blinded")

        init {
            assertEquals(setOf("attestation", "blinded"), fields.keys)
            assertEquals(setOf("challenge", "chain", "signature"), attestation.keys)
            (listOf(challenge, signature) + chain + blinded).forEach { strict.decode(it) }
        }

        fun assertSignedBy(key: KeyPair) {
            val message = KeyAttestation.signedMessage(strict.decode(challenge), blinded.map { strict.decode(it) })
            val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(key.public) }
            verifier.update(message)
            assertTrue(verifier.verify(strict.decode(signature)), "signed by the install's key over what was sent")
        }

        private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

        private fun JsonObject.texts(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.content }
    }

    /** RFC 9578's math is zecSwap's to test: a request here is random bytes, and the issuer's echo finalizes it. */
    private object Blinding : PrivacyPassClient {
        override fun challenge(header: String): TokenChallenge = error("prefetching reads no maker's header")

        override fun blind(
            challenge: ByteArray,
            tokenKey: ByteArray
        ) = Random.nextBytes(BLINDED_BYTES).let { BlindedToken(it, it) }

        override fun finalize(
            pending: ByteArray,
            tokenKey: ByteArray,
            blindSignature: ByteArray
        ) = pending.also { check(it.contentEquals(blindSignature)) }

        override fun authorization(token: ByteArray): String = error("prefetching spends nothing")
    }

    private companion object {
        const val NAME = "zecswap-testnet-issuer"
        const val KEY_CHALLENGE = "f5cbc5e3281d0519f8ae7efb241c7c10b7d2d52f3bde46703d1ecd0fd5613488"
        const val NOW_SECONDS = 1_790_000_000L
        const val NOW_MILLIS = NOW_SECONDS * 1000
        const val TODAY = NOW_SECONDS / 86_400
        const val YEAR = 365 * 86_400_000L
        const val CHALLENGE_BYTES = 32
        const val BLINDED_BYTES = 256
        val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val strict = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val TOKEN_KEY = base64Url.encode("a fake token key".encodeToByteArray())
        val ISSUER =
            SwapTokenIssuer(
                Url("https://tokens/issuer"),
                NAME,
                TOKEN_KEY,
                base64Url.encode("a fake return key".encodeToByteArray())
            )
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")

        // As zecSwap's issuer says them.
        val REFUSALS =
            mapOf(
                HttpStatusCode.Forbidden to """{"code":"rejected","error":"a phone with its bootloader unlocked"}""",
                HttpStatusCode.TooManyRequests to """{"code":"unavailable","error":"this device's tokens are spent"}""",
            )

        /** RFC 9577's challenge, dated as zecSwap's makers date theirs: today in the last 8 bytes of its context. */
        val MAKER_CHALLENGE =
            run {
                val name = NAME.encodeToByteArray()
                val day = ByteArray(8) { (TODAY ushr 8 * (7 - it)).toByte() }
                val origin = byteArrayOf(0, 5) + "maker".encodeToByteArray()
                val context = byteArrayOf(32) + ByteArray(24) + day
                base64Url.encode(byteArrayOf(0, 2, 0, name.size.toByte()) + name + context + origin)
            }
    }
}
