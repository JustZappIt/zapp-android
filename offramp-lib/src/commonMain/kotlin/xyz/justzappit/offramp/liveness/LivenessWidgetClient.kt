// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.justzappit.evm.types.Address

data class LivenessWidgetSession(
    val widgetUrl: String,
    val expiresInSeconds: Int,
)

class LivenessException(
    override val message: String,
    val status: Int? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    /** The service's own reason, e.g. `redirect_uri_not_allowlisted` or `unknown_code`. */
    val isClientRejection: Boolean get() = status == HttpStatusCode.BadRequest.value
}

/**
 * The two key-gated calls of the widget handoff: open a session for a wallet, and redeem the
 * one-time code the widget hands back for the signed attestation.
 */
class LivenessWidgetClient(
    private val httpClient: HttpClient,
    private val config: LivenessConfig,
    private val redirectUri: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createSession(wallet: Address, state: String): LivenessWidgetSession {
        val body =
            buildJsonObject {
                put("tenant", config.tenant)
                put("wallet_pubkey", wallet.checksumHex)
                put("redirect_uri", redirectUri)
                put("state", state)
            }
        val response = post(PATH_SESSIONS, body)
        return LivenessWidgetSession(
            widgetUrl = response.string("widget_url"),
            expiresInSeconds = response.string("expires_in").toInt(),
        )
    }

    /** Null when the tenant is not bound to a contract: the service approved, but has nothing to put on chain. */
    suspend fun redeem(code: String): LivenessAttestation? {
        val response = post(PATH_RESULT, buildJsonObject { put("code", code) })
        val attestation = response["attestation"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        return try {
            LivenessAttestation.fromJson(attestation)
        } catch (e: IllegalArgumentException) {
            throw LivenessException("liveness service returned an unusable attestation", cause = e)
        }
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject {
        val response =
            httpClient.post(config.apiUrl.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                header(API_KEY_HEADER, config.apiKey)
                setBody(Json.encodeToString(JsonObject.serializer(), body))
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw LivenessException(
                "liveness service refused $path (${response.status.value})",
                status = response.status.value,
            )
        }
        return parseObject(text, path)
    }

    private fun parseObject(text: String, path: String): JsonObject =
        try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw LivenessException("liveness service returned an unreadable body for $path", cause = e)
        } catch (e: IllegalArgumentException) {
            throw LivenessException("liveness service returned an unreadable body for $path", cause = e)
        }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.content ?: throw LivenessException("liveness service response has no $name")

    private companion object {
        const val API_KEY_HEADER = "X-API-Key"
        const val PATH_SESSIONS = "/v1/widget/sessions"
        const val PATH_RESULT = "/v1/widget/result"
    }
}
