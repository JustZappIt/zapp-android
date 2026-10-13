// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.reputation.IdentityCheck

/** Where one check's widget is opened: p2p.me's public proxy, and the tenant that signs for the RM. */
data class IdentityService(
    val baseUrl: String,
    val tenant: String,
)

data class IdentityServices(
    val liveness: IdentityService?,
    val passport: IdentityService?,
) {
    fun of(check: IdentityCheck): IdentityService? =
        when (check) {
            IdentityCheck.Liveness -> liveness
            IdentityCheck.Passport -> passport
        }

    companion object {
        /** p2p.me's own values; the proxies inject the API key, so nothing here is secret. */
        val MAINNET =
            IdentityServices(
                liveness = IdentityService("https://liveness-proxy.p2p.cool", "p2p-reputation-liveness-mainnet"),
                passport = IdentityService("https://passport-proxy.p2p.cool", "p2p-reputation-mainnet"),
            )

        val NONE = IdentityServices(liveness = null, passport = null)
    }
}

class IdentityException(
    override val message: String,
    val status: Int? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    val isClientRejection: Boolean get() = status != null && status in CLIENT_ERRORS

    private companion object {
        val CLIENT_ERRORS = 400..499
    }
}

/**
 * The two calls of the widget handoff: open a session for a wallet, and redeem the one-time code
 * the widget redirects back with for the signed attestation.
 */
class IdentityWidgetClient(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The widget URL. [country] is required by the passport widget and ignored by liveness. */
    suspend fun createSession(
        service: IdentityService,
        wallet: Address,
        redirectUri: String,
        state: String,
        country: String?,
    ): String {
        val body =
            buildJsonObject {
                put("wallet_pubkey", wallet.checksumHex)
                put("redirect_uri", redirectUri)
                put("tenant", service.tenant)
                country?.let { put("country", it) }
                put("state", state)
            }
        return post(service, PATH_SESSIONS, body).string("widget_url")
    }

    suspend fun redeem(service: IdentityService, code: String): IdentityAttestation {
        val response = post(service, PATH_ATTESTATION, buildJsonObject { put("code", code) })
        return try {
            IdentityAttestation.fromJson(response)
        } catch (e: IllegalArgumentException) {
            throw IdentityException("identity service returned an unusable attestation", cause = e)
        }
    }

    private suspend fun post(service: IdentityService, path: String, body: JsonObject): JsonObject {
        val response =
            httpClient.post(service.baseUrl.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(JsonObject.serializer(), body))
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IdentityException("identity service refused $path (${response.status.value})", response.status.value)
        }
        return parse(text, path)
    }

    private fun parse(text: String, path: String): JsonObject =
        try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw IdentityException("identity service returned an unreadable body for $path", cause = e)
        } catch (e: IllegalArgumentException) {
            throw IdentityException("identity service returned an unreadable body for $path", cause = e)
        }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.content ?: throw IdentityException("identity service response has no $name")

    private companion object {
        const val PATH_SESSIONS = "/v1/widget/public-sessions"
        const val PATH_ATTESTATION = "/v1/widget/attestation"
    }
}
