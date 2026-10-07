// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** The issuer a deployment's maker takes Privacy Pass tokens from (zecSwap's `docs/tokens.md`), pinned in the build. */
@Serializable
data class SwapTokenIssuer(
    val url: Url,
    /** The name its token challenges give it. */
    val name: String,
    /** Its RFC 9578 token key, base64url SPKI: a challenge for any other, or a publication of one, is refused. */
    val tokenKey: String,
    val attestation: TokenAttestation,
)

/** How this build shows the issuer it's a genuine install. */
@Serializable
enum class TokenAttestation {
    /** Bytes the issuer takes for a device's id unchecked: until an install can be attested, it limits nothing. */
    @SerialName("insecure-test")
    INSECURE_TEST,
}

/** What vouches for this device to the token issuer, and to no one else. */
fun interface DeviceAttestation {
    suspend fun attestation(): ByteArray
}

/** RFC 9578's client side (token type 0x0002), as zecSwap's native library computes it. */
interface PrivacyPassClient {
    /** What a `WWW-Authenticate: PrivateToken` header asks for. */
    fun challenge(header: String): TokenChallenge

    /** A fresh token request for [challenge], blinded under [tokenKey]. */
    fun blind(
        challenge: ByteArray,
        tokenKey: ByteArray
    ): BlindedToken

    /** The token [pending] becomes once the issuer's [blindSignature] unblinds to a valid one under [tokenKey]. */
    fun finalize(
        pending: ByteArray,
        tokenKey: ByteArray,
        blindSignature: ByteArray
    ): ByteArray

    /** The `Authorization` value that spends [token]. */
    fun authorization(token: ByteArray): String
}

/** A token challenge's encoding, the issuer it names, and the key it asks tokens under. */
class TokenChallenge(
    val bytes: ByteArray,
    val issuer: String,
    val tokenKey: ByteArray,
)

/** A token request: [blinded] goes to the issuer, [pending] stays to finalize its answer. */
class BlindedToken(
    val blinded: ByteArray,
    val pending: ByteArray,
) {
    override fun toString() = "BlindedToken"
}

/** Spends a token on what a maker's `WWW-Authenticate` [challenge] asks, as the `Authorization` value to send. */
fun interface SwapTokenSource {
    suspend fun spend(challenge: String): String
}

/** Where tokens wait between runs, encrypted: each lost is one of the day's few conversions gone. */
interface SwapTokenStore {
    suspend fun load(): SwapTokenState

    suspend fun save(state: SwapTokenState)
}

@Serializable
data class SwapTokenState(
    /** The last challenge a maker sent, base64url: tokens are fetched ahead for it. */
    val challenge: String? = null,
    val held: List<HeldToken> = emptyList(),
    /** The UTC day, counted from the epoch, on which the issuer had no more tokens for this device. */
    val exhaustedDay: Long? = null,
)

/** An unspent token, base64url, with the challenge and key it answers. */
@Serializable
data class HeldToken(
    val challenge: String,
    val tokenKey: String,
    val token: String,
) {
    override fun toString() = "HeldToken"
}

/**
 * Privacy Pass tokens for the maker's accepts: one a conversion, a few a day, signed blind by the pinned [issuer] so
 * that neither it nor the maker can tie a conversion to this device. Fetched ahead where possible, over [http]'s route.
 */
class SwapTokens(
    private val issuer: SwapTokenIssuer,
    private val http: suspend () -> HttpClient,
    private val crypto: PrivacyPassClient,
    private val attestation: DeviceAttestation,
    private val store: SwapTokenStore,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : SwapTokenSource {
    private val lock = Mutex()
    private val tokenKey = base64Url.decode(issuer.tokenKey)

    /** A token held for the challenge, or one fetched now if none is; dropped before it's sent, so it goes once. */
    override suspend fun spend(challenge: String): String =
        lock.withLock {
            val asked = pinned(crypto.challenge(challenge))
            var state = store.load().copy(challenge = asked).also { store.save(it) }
            if (state.heldFor(asked).isEmpty()) state = fetched(state, asked)
            val token = state.heldFor(asked).first()
            store.save(state.copy(held = state.held - token))
            crypto.authorization(base64Url.decode(token.token))
        }

    /** Tokens for the last challenge a maker sent, fetched well before one is spent; nothing until a maker asked. */
    suspend fun prefetch() =
        lock.withLock {
            val state = store.load()
            val asked = state.challenge
            if (asked != null && state.heldFor(asked).isEmpty() && state.exhaustedDay != today()) fetched(state, asked)
        }

    // A challenge naming another issuer or key would mark this device's tokens: nothing is fetched or spent for one.
    private fun pinned(challenge: TokenChallenge): String {
        if (challenge.issuer != issuer.name || !challenge.tokenKey.contentEquals(tokenKey)) {
            throw refused("the maker asks for tokens the pinned issuer doesn't sign")
        }
        return base64Url.encode(challenge.bytes)
    }

    private fun SwapTokenState.heldFor(challenge: String) =
        held.filter { it.challenge == challenge && it.tokenKey == issuer.tokenKey }

    private suspend fun fetched(
        state: SwapTokenState,
        challenge: String
    ): SwapTokenState {
        val today = today()
        if (state.exhaustedDay == today) throw exhausted()
        val client = http()
        val signed =
            try {
                val perDay = published(client).tokensPerDay.coerceIn(1, MAX_BATCH)
                val requests = List(perDay) { crypto.blind(base64Url.decode(challenge), tokenKey) }
                requests to issued(client, requests, state, today)
            } finally {
                client.close()
            }
        val (requests, signatures) = signed
        val tokens =
            requests.zip(signatures) { request, signature ->
                val token = crypto.finalize(request.pending, tokenKey, base64Url.decode(signature))
                HeldToken(challenge, issuer.tokenKey, base64Url.encode(token))
            }
        // The issuer signs only what the day has left: a short batch spent it.
        val exhaustedDay = if (signatures.size < requests.size) today else state.exhaustedDay
        return state.copy(held = state.held + tokens, exhaustedDay = exhaustedDay).also { store.save(it) }
    }

    // Takes only the key the issuer publishes to everyone: one shown to some devices alone would mark their tokens.
    private suspend fun published(client: HttpClient): IssuerTokenKey {
        val answer = exchange { client.get(issuer.url.path("/v1/token-key")) { expectSuccess = false } }
        val published = decoded(answer, IssuerTokenKey.serializer())
        if (published.issuer != issuer.name || !base64Url.decode(published.tokenKey).contentEquals(tokenKey)) {
            throw refused("the issuer publishes another key than the pinned one")
        }
        return published
    }

    private suspend fun issued(
        client: HttpClient,
        requests: List<BlindedToken>,
        state: SwapTokenState,
        today: Long,
    ): List<String> {
        val body =
            TokenRequests(base64Url.encode(attestation.attestation()), requests.map { base64Url.encode(it.blinded) })
        val answer =
            exchange {
                client.post(issuer.url.path("/v1/tokens")) {
                    expectSuccess = false
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(TokenRequests.serializer(), body))
                }
            }
        if (answer.status == HttpStatusCode.TooManyRequests) store.save(state.copy(exhaustedDay = today))
        refusal(answer.status)?.let { throw it }
        val signatures = decoded(answer, TokenResponses.serializer()).blindSignatures
        if (signatures.size !in 1..requests.size) throw unavailable("the issuer signed ${signatures.size} tokens")
        return signatures
    }

    private suspend fun exchange(request: suspend () -> HttpResponse): IssuerAnswer =
        try {
            request().let { IssuerAnswer(it.status, it.bodyAsText()) }
        } catch (e: IOException) {
            throw unavailable("the token issuer is unreachable", e)
        }

    private fun <T> decoded(
        answer: IssuerAnswer,
        serializer: KSerializer<T>
    ): T {
        refusal(answer.status)?.let { throw it }
        return try {
            json.decodeFromString(serializer, answer.body)
        } catch (e: IllegalArgumentException) {
            throw unavailable("the token issuer's answer doesn't read", e)
        }
    }

    private fun today() = nowSeconds() / SECONDS_PER_DAY

    private class IssuerAnswer(
        val status: HttpStatusCode,
        val body: String,
    )

    private companion object {
        const val SECONDS_PER_DAY = 24 * 60 * 60L

        // The most one request may ask the issuer for.
        const val MAX_BATCH = 100
        val json = Json { ignoreUnknownKeys = true }

        fun Url.path(path: String) = toString().trimEnd('/') + path

        fun refusal(status: HttpStatusCode): AtomicSwapBlockedException? =
            when {
                status == HttpStatusCode.TooManyRequests -> exhausted()
                status == HttpStatusCode.Forbidden -> refused("the issuer refused this device's attestation")
                !status.isSuccess() -> unavailable("the token issuer answered ${status.value}")
                else -> null
            }
    }
}

/** A deployment with no pinned issuer: a maker that asks it for a token is refused. */
internal val NO_TOKENS = SwapTokenSource { throw refused("no token issuer is pinned for this deployment") }

@Serializable
internal class IssuerTokenKey(
    val issuer: String,
    val tokenKey: String,
    val tokensPerDay: Int,
)

@Serializable
internal class TokenRequests(
    val attestation: String,
    val blinded: List<String>,
)

@Serializable
internal class TokenResponses(
    val blindSignatures: List<String>,
)

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

private fun exhausted() =
    AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_EXHAUSTED, "this device's tokens for today are spent")

private fun refused(why: String) = AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_REFUSED, why)

private fun unavailable(
    why: String,
    cause: Throwable? = null
) = AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_UNAVAILABLE, why, cause)
