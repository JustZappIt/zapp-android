// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** The issuer a deployment's maker takes Privacy Pass tokens from (zecSwap's `docs/tokens.md`), pinned in the build. */
@Serializable
data class SwapTokenIssuer(
    val url: Url,
    /** The name its token challenges give it, and this install's key is made for. */
    val name: String,
    /** Its RFC 9578 token key, base64url SPKI: a challenge for any other, or a publication of one, is refused. */
    val tokenKey: String,
    /** The key the maker hands tokens back under, base64url SPKI: a maker whose `/v1/info` shows another is refused. */
    val returnKey: String,
)

/** How this install proves to the token issuer, and to no one else, that it's genuine: zecSwap's `Attest`. */
fun interface DeviceAttestation {
    /** Gets ready to attest ahead of the first fetch, which waits for a maker's challenge. */
    suspend fun prepare() = Unit

    /** This install's attestation of a request carrying [blinded], for the issuer's [challenge], as given. */
    suspend fun attest(
        challenge: String,
        blinded: List<ByteArray>
    ): KeyAttestation
}

/** An install's key, attested by the phone's secure hardware up to Google's root, signing the request it comes with. */
@Serializable
class KeyAttestation(
    /** The issuer's challenge, base64url, as given. */
    val challenge: String,
    /** The key's certificate chain, leaf first, each DER, base64url. */
    val chain: List<String>,
    /** `SHA256withECDSA` by the key over [signedMessage], DER, base64url. */
    val signature: String,
) {
    override fun toString() = "KeyAttestation"

    companion object {
        private val CONTEXT = "zecswap-issuer-v1".encodeToByteArray()

        /** The attestation challenge of a key that counts at the issuer named [issuer], and at no other. */
        fun keyChallenge(issuer: String): ByteArray = sha256(CONTEXT + issuer.encodeToByteArray())

        /**
         * What a request's signature covers: the issuer's [challenge], decoded, and the request's [blinded] messages in
         * the order sent.
         */
        fun signedMessage(
            challenge: ByteArray,
            blinded: List<ByteArray>
        ): ByteArray = CONTEXT + challenge + sha256(blinded.fold(ByteArray(0), ByteArray::plus))
    }
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

/** Pays for a maker's accepts with tokens, and asks each one back. */
interface SwapTokenSource {
    /** A token for [swapId]'s accept, as a maker's `WWW-Authenticate` [challenge] asks, and a request for it back. */
    suspend fun pay(
        swapId: SwapId,
        challenge: String
    ): TokenPayment

    /** Settles [payment] by the maker's answer, null if none came. */
    suspend fun settle(
        payment: TokenPayment,
        status: HttpStatusCode?
    )

    /** Whether [swapId]'s accept asked for its token back and hasn't had it yet. */
    suspend fun awaitsReturn(swapId: SwapId): Boolean

    /** Holds the token [swapId]'s status hands back, its maker's blind signature [tokenReturn], if it does. */
    suspend fun collect(
        swapId: SwapId,
        tokenReturn: String?
    )
}

/** What an accept pays with: the `Authorization` value spending a token, and the blinded [request] for one back. */
class TokenPayment internal constructor(
    val authorization: String,
    val request: String,
    internal val token: HeldToken,
    internal val pending: PendingReturn,
) {
    override fun toString() = "TokenPayment"
}

/** Where tokens wait between runs, encrypted: each lost is one of the day's few conversions gone. */
interface SwapTokenStore {
    suspend fun load(): SwapTokenState

    suspend fun save(state: SwapTokenState)
}

@Serializable
data class SwapTokenState(
    /** The last challenge a maker sent, base64url: tokens are fetched ahead for it, on the day the fetch is for. */
    val challenge: String? = null,
    val held: List<HeldToken> = emptyList(),
    /** The UTC day, counted from the epoch, on which the issuer had no more tokens for this device. */
    val exhaustedDay: Long? = null,
    /** Each paid accept's request for its token back; more than one for a swap whose accept was tried again. */
    val returns: List<PendingReturn> = emptyList(),
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

/** A paid accept's request for its token back: the client's half, base64url, which finalizes the maker's signature. */
@Serializable
data class PendingReturn(
    val swapId: SwapId,
    val challenge: String,
    val pending: String,
) {
    override fun toString() = "PendingReturn"
}

/**
 * Privacy Pass tokens for the maker's accepts, signed blind by the pinned [issuer] so that neither it nor the maker can
 * tie a conversion to this device: one an accept, handed back once the user pays in, so a device walks away from a few
 * conversions a day at most. Fetched ahead where possible, over [http]'s route.
 */
class SwapTokens(
    private val issuer: SwapTokenIssuer,
    http: suspend () -> HttpClient,
    private val crypto: PrivacyPassClient,
    private val attestation: DeviceAttestation,
    private val store: SwapTokenStore,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : SwapTokenSource {
    private val lock = Mutex()
    private val tokenKey = base64Url.decode(issuer.tokenKey)
    private val returnKey = base64Url.decode(issuer.returnKey)
    private val issuance = SwapTokenIssuance(issuer, http, crypto, attestation)

    /** The issuer's tokens first, fetched now if none is held; then, once it has none left today, one handed back. */
    override suspend fun pay(
        swapId: SwapId,
        challenge: String
    ): TokenPayment =
        lock.withLock {
            val asked = pinned(crypto.challenge(challenge))
            val day = requireToday(asked)
            val encoded = base64Url.encode(asked)
            var state = store.load().since(day).copy(challenge = encoded)
            store.save(state)
            if (state.issued(encoded).isEmpty() && state.exhaustedDay != day) {
                state = issuance.fetched(state, encoded, day).also { store.save(it) }
            }
            val token = state.issued(encoded).firstOrNull() ?: state.returned(encoded, day).firstOrNull()
            if (token == null) throw exhausted()
            val request = crypto.blind(asked, returnKey)
            val pending = PendingReturn(swapId, encoded, base64Url.encode(request.pending))
            store.save(state.copy(returns = state.returns + pending))
            TokenPayment(
                crypto.authorization(base64Url.decode(token.token)),
                base64Url.encode(request.blinded),
                token,
                pending,
            )
        }

    /**
     * A token the maker took, or refused with a 401, is gone. Any other answer, or none, left it spendable: it stays
     * first in line, and a maker that kept it after all refuses it next time. The request for it back stays unless the
     * maker refused the token, which it then never saw.
     */
    override suspend fun settle(
        payment: TokenPayment,
        status: HttpStatusCode?
    ) = lock.withLock {
        val refused = status == HttpStatusCode.Unauthorized
        if (!refused && status?.isSuccess() != true) return@withLock
        val state = store.load()
        store.save(
            state.copy(
                held = state.held.filterNot { it.token == payment.token.token },
                returns = if (refused) state.returns - payment.pending else state.returns,
            ),
        )
    }

    override suspend fun awaitsReturn(swapId: SwapId): Boolean =
        lock.withLock {
            val returns = store.load().since(earliestDay(nowSeconds())).returns
            returns.any { it.swapId == swapId }
        }

    /** The token back, held for its accept's challenge apart from the issuer's. */
    override suspend fun collect(
        swapId: SwapId,
        tokenReturn: String?
    ) {
        if (tokenReturn == null) return
        lock.withLock {
            val state = store.load().since(earliestDay(nowSeconds()))
            val requests = state.returns.filter { it.swapId == swapId }
            // The maker signed one of the swap's requests: the one its accept went through with.
            val returned =
                requests.firstNotNullOfOrNull { request ->
                    finalized(request, tokenReturn)?.let { token ->
                        HeldToken(request.challenge, issuer.returnKey, base64Url.encode(token))
                    }
                }
            val held = state.held + listOfNotNull(returned)
            store.save(state.copy(held = held, returns = state.returns - requests.toSet()))
        }
    }

    /** Tokens for today's challenge, fetched well before one is spent: the last a maker sent, moved to today. */
    suspend fun prefetch() {
        attestation.prepare()
        lock.withLock {
            val today = day(nowSeconds())
            val state = store.load().since(earliestDay(nowSeconds()))
            val last = state.challenge?.decoded()
            val moved = last?.let { if (it.challengeDay() == today) it else it.challengeOn(today) }
            val asked = moved?.let(base64Url::encode)
            val current = state.copy(challenge = asked ?: state.challenge)
            store.save(current)
            if (asked != null && current.issued(asked).isEmpty() && current.exhaustedDay != today) {
                store.save(issuance.fetched(current, asked, today))
            }
        }
    }

    // A challenge naming another issuer or key would mark this device's tokens: nothing is fetched or spent for one.
    private fun pinned(challenge: TokenChallenge): ByteArray {
        if (challenge.issuer != issuer.name || !challenge.tokenKey.contentEquals(tokenKey)) {
            throw refused("the maker asks for tokens the pinned issuer doesn't sign")
        }
        return challenge.bytes
    }

    // So would a day only some devices were asked for: only today's is taken, give or take the minutes either side of
    // midnight the maker's clock may be off by.
    private fun requireToday(challenge: ByteArray): Long {
        val now = nowSeconds()
        val asked = challenge.challengeDay()
        if (asked == null || asked !in day(now - CLOCK_SKEW_SECONDS)..day(now + CLOCK_SKEW_SECONDS)) {
            throw refused("the maker asks for tokens for another day")
        }
        return asked
    }

    private fun SwapTokenState.issued(challenge: String) =
        held.filter { it.challenge == challenge && it.tokenKey == issuer.tokenKey }

    // Handed back: spent only once the issuer has none left for the day.
    private fun SwapTokenState.returned(
        challenge: String,
        day: Long
    ) = held.filter { exhaustedDay == day && it.challenge == challenge && it.tokenKey == issuer.returnKey }

    private fun finalized(
        request: PendingReturn,
        signature: String
    ): ByteArray? =
        try {
            crypto.finalize(base64Url.decode(request.pending), returnKey, base64Url.decode(signature))
        } catch (_: AtomicSwapBlockedException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val SECONDS_PER_DAY = 24 * 60 * 60L
        const val CLOCK_SKEW_SECONDS = 10 * 60L

        fun day(unixSeconds: Long) = unixSeconds / SECONDS_PER_DAY

        // The first day the maker's clock may still be on.
        fun earliestDay(now: Long) = day(now - CLOCK_SKEW_SECONDS)

        // Earlier days' tokens are refused, and so would be those their requests become.
        fun SwapTokenState.since(day: Long) =
            copy(
                held = held.filter { it.challenge.isOnOrAfter(day) },
                returns = returns.filter { it.challenge.isOnOrAfter(day) },
            )

        fun String.isOnOrAfter(day: Long) = decoded()?.challengeDay()?.let { it >= day } == true
    }
}

/** The pinned issuer's API: the key it publishes, and batches of tokens it signs blind for this device. */
internal class SwapTokenIssuance(
    private val issuer: SwapTokenIssuer,
    private val http: suspend () -> HttpClient,
    private val crypto: PrivacyPassClient,
    private val attestation: DeviceAttestation,
) {
    private val tokenKey = base64Url.decode(issuer.tokenKey)

    /** [state] with a batch for [challenge] added, as many as the device's [day] has left, the day marked if spent. */
    suspend fun fetched(
        state: SwapTokenState,
        challenge: String,
        day: Long,
    ): SwapTokenState {
        val client = route()
        val signed =
            try {
                val perDay = published(client).tokensPerDay.coerceIn(1, MAX_BATCH)
                val requests = List(perDay) { crypto.blind(base64Url.decode(challenge), tokenKey) }
                requests to issued(client, requests)
            } finally {
                client.close()
            }
        val (requests, signatures) = signed
        val tokens =
            requests.zip(signatures) { request, signature ->
                val token = crypto.finalize(request.pending, tokenKey, signature)
                HeldToken(challenge, issuer.tokenKey, base64Url.encode(token))
            }
        // The issuer signs only what the day has left: a short batch, or none, spent it.
        val exhaustedDay = if (signatures.size < requests.size) day else state.exhaustedDay
        return state.copy(held = state.held + tokens, exhaustedDay = exhaustedDay)
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

    // None once the issuer has nothing left for this device today.
    private suspend fun issued(
        client: HttpClient,
        requests: List<BlindedToken>,
    ): List<ByteArray> {
        val blinded = requests.map { it.blinded }
        val body = TokenRequests(attestation.attest(challenge(client), blinded), blinded.map(base64Url::encode))
        val answer =
            exchange {
                client.post(issuer.url.path("/v1/tokens")) {
                    expectSuccess = false
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(TokenRequests.serializer(), body))
                }
            }
        if (answer.status == HttpStatusCode.TooManyRequests) return emptyList()
        val signatures = decoded(answer, TokenResponses.serializer()).blindSignatures
        if (signatures.size !in 1..requests.size) throw unavailable("the issuer signed ${signatures.size} tokens")
        return signatures.map { it.decoded() ?: throw unavailable("the token issuer's answer doesn't read") }
    }

    // Good for one request, for minutes: asked for right before each.
    private suspend fun challenge(client: HttpClient): String {
        val answer = exchange { client.get(issuer.url.path("/v1/challenge")) { expectSuccess = false } }
        val challenge = decoded(answer, AttestationChallenge.serializer()).challenge
        if (challenge.decoded()?.size != CHALLENGE_BYTES) throw unavailable("the token issuer's challenge doesn't read")
        return challenge
    }

    private suspend fun route(): HttpClient =
        try {
            http()
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Tor that won't start fails with a plain Exception; Tor that's off is already a blocked step.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw e as? AtomicSwapBlockedException ?: unavailable("no route to the token issuer", e)
        }

    private suspend fun exchange(request: suspend () -> HttpResponse): IssuerAnswer =
        try {
            request().let { IssuerAnswer(it.status, it.bodyAsText()) }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Tor's client fails with RuntimeExceptions, not IOExceptions.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw unavailable("the token issuer is unreachable", e)
        }

    private fun <T> decoded(
        answer: IssuerAnswer,
        serializer: KSerializer<T>
    ): T {
        refusal(answer)?.let { throw it }
        return try {
            json.decodeFromString(serializer, answer.body)
        } catch (e: IllegalArgumentException) {
            throw unavailable("the token issuer's answer doesn't read", e)
        }
    }

    private class IssuerAnswer(
        val status: HttpStatusCode,
        val body: String,
    )

    private companion object {
        // The most one request may ask the issuer for.
        const val MAX_BATCH = 100
        const val CHALLENGE_BYTES = 32
        val json = Json { ignoreUnknownKeys = true }

        fun Url.path(path: String) = toString().trimEnd('/') + path

        fun refusal(answer: IssuerAnswer): AtomicSwapBlockedException? {
            if (answer.status.isSuccess()) return null
            val reason =
                try {
                    json.decodeFromString(ServiceError.serializer(), answer.body).error
                } catch (_: IllegalArgumentException) {
                    null
                }
            val answered = "the token issuer answered ${answer.status.value}" + reason?.let { ": $it" }.orEmpty()
            return when (answer.status) {
                HttpStatusCode.TooManyRequests -> exhausted()
                HttpStatusCode.Forbidden -> refused(answered)
                else -> unavailable(answered)
            }
        }
    }
}

/** A deployment with no pinned issuer: a maker that asks it for a token is refused. */
internal val NO_TOKENS =
    object : SwapTokenSource {
        override suspend fun pay(
            swapId: SwapId,
            challenge: String
        ): TokenPayment = throw refused("no token issuer is pinned for this deployment")

        override suspend fun settle(
            payment: TokenPayment,
            status: HttpStatusCode?
        ) = Unit

        override suspend fun awaitsReturn(swapId: SwapId) = false

        override suspend fun collect(
            swapId: SwapId,
            tokenReturn: String?
        ) = Unit
    }

@Serializable
internal class IssuerTokenKey(
    val issuer: String,
    val tokenKey: String,
    val tokensPerDay: Int,
)

@Serializable
internal class AttestationChallenge(
    val challenge: String,
)

@Serializable
internal class TokenRequests(
    val attestation: KeyAttestation,
    val blinded: List<String>,
)

@Serializable
internal class TokenResponses(
    val blindSignatures: List<String>,
)

// RFC 9577's TokenChallenge: the token type, then the issuer's name after its u16 length, a 32-byte redemption context
// and the origin's name. Ours are dated: the context is 24 zero bytes, then the UTC day as a big-endian u64.
private const val ISSUER_LENGTH_AT = 2
private const val CONTEXT_BYTES = 32
private const val DAY_BYTES = 8
private const val BYTE_MASK = 0xFFL

/** The UTC day a dated challenge is for, or null for any other. */
private fun ByteArray.challengeDay(): Long? = dayAt()?.let { number(it, DAY_BYTES) }

/** The same challenge for [day]. */
private fun ByteArray.challengeOn(day: Long): ByteArray? =
    dayAt()?.let { at ->
        copyOf().also { moved ->
            repeat(DAY_BYTES) { moved[at + it] = (day ushr Byte.SIZE_BITS * (DAY_BYTES - 1 - it)).toByte() }
        }
    }

private fun ByteArray.dayAt(): Int? {
    val issuerAt = ISSUER_LENGTH_AT + Short.SIZE_BYTES
    if (size < issuerAt) return null
    val context = issuerAt + number(ISSUER_LENGTH_AT, Short.SIZE_BYTES).toInt()
    val at = context + 1 + CONTEXT_BYTES - DAY_BYTES
    val isDated =
        size >= at + DAY_BYTES + Short.SIZE_BYTES &&
            this[context].toInt() == CONTEXT_BYTES &&
            (context + 1 until at).all { this[it] == 0.toByte() }
    return at.takeIf { isDated }
}

private fun ByteArray.number(
    from: Int,
    bytes: Int
): Long = copyOfRange(from, from + bytes).fold(0L) { n, byte -> n shl Byte.SIZE_BITS or (byte.toLong() and BYTE_MASK) }

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

private fun sha256(bytes: ByteArray) =
    CryptographyProvider.Default
        .get(SHA256)
        .hasher()
        .hashBlocking(bytes)

private fun String.decoded(): ByteArray? =
    try {
        base64Url.decode(this)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun exhausted() =
    AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_EXHAUSTED, "this device's tokens for today are spent")

private fun refused(why: String) = AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_REFUSED, why)

private fun unavailable(
    why: String,
    cause: Throwable? = null
) = AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_UNAVAILABLE, why, cause)
