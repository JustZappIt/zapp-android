// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.padLeftToWord
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.reputation.SocialPlatform

/** A live Reclaim session: where to send the user, and what to poll while they are gone. */
data class ReclaimSession(
    val sessionId: String,
    val requestUrl: String,
    /**
     * Which version of the provider script Reclaim chose — the caller does not get a say. A bump
     * can change proof shape, so it is carried with every proof: without it a wave of reverts has
     * no common thread to find.
     */
    val resolvedProviderVersion: String,
)

class ReclaimException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Mints a Reclaim session on-device: seven HTTP-and-one-signature steps, no JS, no WebView, no
 * Reclaim SDK. Ported from `@reclaimprotocol/js-sdk` 5.8.2, whose behaviour is what the Verifier
 * app and Reclaim's backend actually agree on.
 *
 * The single fact that decides whether any of this lands is [contextAddress]: the on-chain
 * verifier returns the address in the proof's context, and the ReputationManager requires it to
 * equal `msg.sender`. Zapp submits through a sponsored UserOperation, so `msg.sender` is the
 * **smart account** — never the owner EOA that signs it. A session minted for the EOA reverts
 * "User address mismatch" five minutes later, naming nothing the user can act on.
 */
class ReclaimSessionMinter(
    private val httpClient: HttpClient,
    private val credentials: ReclaimAppCredentials,
    private val nowMillis: () -> Long,
    private val baseUrl: String = RECLAIM_API_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun mint(platform: SocialPlatform, contextAddress: Address): ReclaimSession {
        require(credentials.isConfigured) { "Reclaim app credentials are not configured" }
        val timestamp = nowMillis().toString()
        val signature = signInit(platform.providerId, timestamp)

        val opened = openSession(platform.providerId, timestamp, signature)
        // The TEE nonce hashes the sessionId, so it cannot be derived until the session exists.
        val nonce = attestationNonce(opened.sessionId, timestamp)
        val context = context(nonce, opened.sessionId, timestamp, platform, contextAddress)
        val template =
            templateData(
                sessionId = opened.sessionId,
                providerId = platform.providerId,
                timestamp = timestamp,
                signature = signature,
                context = context,
                resolvedProviderVersion = opened.resolvedProviderVersion.orEmpty(),
            )

        val requestUrl = shorten(shareLink(template))
        // Marks the session started so one the user abandons reports as abandoned rather than as
        // a failure we then have to explain.
        markStarted(opened.sessionId)
        return ReclaimSession(
            sessionId = opened.sessionId,
            requestUrl = requestUrl,
            resolvedProviderVersion = opened.resolvedProviderVersion.orEmpty(),
        )
    }

    /**
     * ☠ The **fixed-length** EIP-191 variant: header `\x19Ethereum Signed Message:\n32` over the
     * 32 raw digest bytes, the same form `Erc4337Submitter` uses for UserOp hashes.
     * `OnrampRequestSigner` deliberately uses the variable-length form and warns that the two
     * recover different addresses; here the wrong one yields a generic init failure that points at
     * nothing.
     */
    internal fun signInit(providerId: String, timestamp: String): String {
        val canonical =
            CanonicalJson.stringify(
                buildJsonObject {
                    put("providerId", providerId)
                    put("timestamp", timestamp)
                },
            )
        val digest = keccak256(canonical.encodeToByteArray())
        val key = EvmKeyDerivation.fromPrivateKey(credentials.appSecret.hexToBytes())
        return try {
            val signature = key.signRecoverable(keccak256(EIP191_FIXED_PREFIX + digest))
            HEX_PREFIX +
                (
                    signature.r.toByteArray().padLeftToWord() +
                        signature.s.toByteArray().padLeftToWord() +
                        byteArrayOf((signature.yParity + V_OFFSET).toByte())
                ).toHex()
        } finally {
            key.zeroize()
        }
    }

    internal fun attestationNonce(sessionId: String, timestamp: String): String {
        val payload =
            listOf(
                ATTESTATION_NONCE_DOMAIN,
                credentials.appId,
                sessionId,
                timestamp,
                credentials.appSecret,
            ).joinToString(":")
        return keccak256(payload.encodeToByteArray()).toHex()
    }

    internal fun context(
        nonce: String,
        sessionId: String,
        timestamp: String,
        platform: SocialPlatform,
        contextAddress: Address,
    ): JsonObject =
        buildJsonObject {
            put("attestationNonce", nonce)
            putJsonObject("attestationNonceData") {
                put("applicationId", credentials.appId)
                put("attestationVersion", TEE_ATTESTATION_VERSION)
                put("sessionId", sessionId)
                put("timestamp", timestamp)
            }
            // §5.2. The smart account, because that is the msg.sender the contract will see.
            put("contextAddress", contextAddress.checksumHex)
            put("contextMessage", "Social verification for ${platform.onChainName}")
            put("reclaimSessionId", sessionId)
        }

    internal fun templateData(
        sessionId: String,
        providerId: String,
        timestamp: String,
        signature: String,
        context: JsonObject,
        resolvedProviderVersion: String,
    ): JsonObject =
        buildJsonObject {
            put("sessionId", sessionId)
            put("providerId", providerId)
            put("applicationId", credentials.appId)
            put("signature", signature)
            put("timestamp", timestamp)
            put("callbackUrl", "$baseUrl$PATH_CALLBACK$sessionId")
            // A string holding JSON, not a nested object: the verifier hashes these bytes.
            put("context", CanonicalJson.stringify(context))
            put("providerVersion", "")
            put("resolvedProviderVersion", resolvedProviderVersion)
            putJsonObject("parameters") {}
            put("redirectUrl", "")
            putJsonObject("redirectUrlOptions") { put("method", "GET") }
            put("cancelCallbackUrl", "$baseUrl$PATH_CANCEL_CALLBACK$sessionId")
            put("cancelRedirectUrl", "")
            putJsonObject("cancelRedirectUrlOptions") { put("method", "GET") }
            put("acceptAiProviders", false)
            // What the Verifier app negotiates against. Pinned, and bumped deliberately.
            put("sdkVersion", SDK_VERSION)
            put("jsonProofResponse", false)
            put("log", false)
            put("canAutoSubmit", true)
            put("acceptTeeAttestation", true)
            put("teeAttestationVersion", TEE_ATTESTATION_VERSION)
        }

    /**
     * `/link/` rather than `/verifier/`: the deferred-deep-link path, which is what lets a user
     * who does not have the Verifier installed land on the store and still arrive at *this*
     * session after installing. The SDK does the same substitution on Android.
     */
    internal fun shareLink(template: JsonObject): String {
        val encoded =
            Json
                .encodeToString(JsonObject.serializer(), template)
                .encodeURLParameter()
                .replace("(", "%28")
                .replace(")", "%29")
        return "$SHARE_LINK_BASE$encoded"
    }

    /**
     * The store fallback, for a device with nothing registered for the https share link.
     *
     * ☠ Plain `market://`, not an `intent://` string. The only consumer is Compose's
     * `LocalUriHandler`, and the stock `AndroidUriHandler` does `startActivity(ACTION_VIEW,
     * Uri.parse(url))` — which resolves nothing for scheme `intent` and throws. Turning an
     * `intent://` string into a launchable Intent needs `Intent.parseUri(…, URI_INTENT_SCHEME)`,
     * which nothing in this app calls.
     *
     * [requestUrl] is deliberately dropped: it only rode along as the `intent://` extra that made
     * the store resume the session after installing, and that never executed. The user comes back
     * to a screen that is still holding the live session anyway.
     *
     * On a build with no Play Store — the `foss` flavour on a de-Googled device — this resolves to
     * nothing either. That is the honest end of the chain: we are already in the branch where no
     * browser handled the https link, so there is nothing left to hand the user.
     */
    fun installIntentUrl(requestUrl: String): String = "market://details?id=$VERIFIER_PACKAGE"

    private suspend fun openSession(providerId: String, timestamp: String, signature: String): InitSessionDto {
        val body =
            buildJsonObject {
                put("providerId", providerId)
                put("appId", credentials.appId)
                put("timestamp", timestamp)
                put("signature", signature)
                put("versionNumber", "")
            }
        val response =
            httpClient.post("$baseUrl$PATH_INIT_SESSION") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(JsonObject.serializer(), body))
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ReclaimException("Reclaim refused the session (${response.status.value})")
        }
        return try {
            json.decodeFromString(InitSessionDto.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ReclaimException("Reclaim returned an unreadable session", e)
        }
    }

    /** Best-effort: the long URL works, so a shortener failure is not worth failing a mint over. */
    private suspend fun shorten(fullUrl: String): String =
        try {
            val response =
                httpClient.post("$baseUrl$PATH_SHORTENER") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject { put("fullUrl", fullUrl) },
                        ),
                    )
                }
            if (!response.status.isSuccess()) {
                fullUrl
            } else {
                json
                    .decodeFromString(ShortenerDto.serializer(), response.bodyAsText())
                    .result
                    ?.shortUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: fullUrl
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            fullUrl
        }

    private suspend fun markStarted(sessionId: String) {
        try {
            httpClient.post("$baseUrl$PATH_UPDATE_SESSION") {
                contentType(ContentType.Application.Json)
                setBody(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("status", SESSION_STARTED)
                        },
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Bookkeeping only. The session is already live and pollable.
        }
    }

    @Serializable
    private data class InitSessionDto(
        val sessionId: String,
        val resolvedProviderVersion: String? = null,
    )

    @Serializable
    private data class ShortenerDto(
        val result: ShortenerResultDto? = null,
    )

    @Serializable
    private data class ShortenerResultDto(
        @SerialName("shortUrl") val shortUrl: String? = null,
    )

    companion object {
        const val RECLAIM_API_BASE = "https://api.reclaimprotocol.org"
        const val VERIFIER_PACKAGE = "org.reclaimprotocol.app"
        const val VERIFIER_STORE_URL = "https://play.google.com/store/apps/details?id=org.reclaimprotocol.app"

        internal const val SDK_VERSION = "js-5.8.2"
        internal const val TEE_ATTESTATION_VERSION = "v3"
        internal const val ATTESTATION_NONCE_DOMAIN = "RECLAIM_TEE_NONCE_V1"
        internal const val SHARE_LINK_BASE = "https://share.reclaimprotocol.org/link/?template="

        private const val PATH_INIT_SESSION = "/api/sdk/init/session/"
        private const val PATH_UPDATE_SESSION = "/api/sdk/update/session/"
        private const val PATH_SHORTENER = "/api/sdk/shortener"
        private const val PATH_CALLBACK = "/api/sdk/callback?callbackId="
        private const val PATH_CANCEL_CALLBACK = "/api/sdk/error-callback?callbackId="
        private const val SESSION_STARTED = "SESSION_STARTED"

        private const val V_OFFSET = 27
        private const val HEX_PREFIX = "0x"
        private const val EIP191_BYTE: Byte = 0x19
        private val EIP191_FIXED_PREFIX =
            byteArrayOf(EIP191_BYTE) + "Ethereum Signed Message:\n32".encodeToByteArray()
    }
}
