// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Peer curator, which turns a payee handle into the on-chain hash a deposit points at. No auth,
 * no API key: a 401 would mean Peer changed policy, not that a header is missing.
 *
 * Three verified traps shape this client:
 *  - `success: true` with `responseObject: false` is a rejection, so the result is never `success`.
 *  - `errorCode` embeds the handle verbatim, which makes it PII. It is matched on shape and
 *    discarded, never logged or returned.
 *  - `validate` cannot say which part of a handle is wrong, so it only ever produces
 *    "we could not confirm this handle".
 */
class PeerCuratorClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    /** Pre-flight before any gas is spent. False means the curator would not accept the handle. */
    suspend fun validatePayee(platform: PeerPlatform, handle: PayeeHandle): Boolean {
        val body = post(PATH_VALIDATE, platform, handle)
        val parsed = decodeOrThrow(ValidateResponseDto.serializer(), body)
        return parsed.responseObject.isAffirmative()
    }

    /** Registers the handle and returns the hash that goes into `paymentMethodData[].payeeDetails`. */
    suspend fun registerPayee(platform: PeerPlatform, handle: PayeeHandle): PayeeHash {
        val body = post(PATH_CREATE, platform, handle)
        val parsed = decodeOrThrow(CreateResponseDto.serializer(), body)
        if (parsed.errorCode.looksLikeMissingProfile()) {
            throw PeerErrorCode.PAYEE_NOT_FOUND_ON_PLATFORM.asException()
        }
        val hash = parsed.responseObject?.hashedOnchainId?.let(PayeeHash::parseOrNull)
        return hash ?: throw PeerErrorCode.PAYEE_REGISTRATION_FAILED.asException()
    }

    private suspend fun post(path: String, platform: PeerPlatform, handle: PayeeHandle): String =
        runPeerCatching {
            httpClient
                .post(baseUrl.trimEnd('/') + path) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            MakerRequestDto.serializer(),
                            MakerRequestDto(processorName = platform.wireName, offchainId = handle.value),
                        ),
                    )
                }.bodyAsText()
        }.getOrElse { throw PeerErrorCode.CURATOR_UNAVAILABLE.asException(cause = it) }

    private fun <T> decodeOrThrow(
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        body: String,
    ): T =
        runCatching { json.decodeFromString(serializer, body) }
            .getOrElse { throw PeerErrorCode.PAYEE_REGISTRATION_FAILED.asException(cause = it) }

    /** Accepts both shapes the curator returns: a bare boolean, or `{ isValid, errors }`. */
    private fun JsonElement?.isAffirmative(): Boolean =
        when (this) {
            null -> false
            is JsonPrimitive -> booleanOrNull == true
            is JsonObject -> this[FIELD_IS_VALID]?.jsonPrimitive?.booleanOrNull == true
            else -> false
        }

    // Matched on shape, never equality: the code interpolates the handle, so an exact comparison
    // would both fail and require holding the PII to build the expected string.
    private fun String?.looksLikeMissingProfile(): Boolean =
        this != null && contains(ERROR_CODE_PROFILE_NOT_FOUND)

    @Serializable
    private data class MakerRequestDto(
        val processorName: String,
        val offchainId: String,
    )

    @Serializable
    private data class ValidateResponseDto(
        val success: Boolean = false,
        val responseObject: JsonElement? = null,
        val statusCode: Int = 0,
    )

    @Serializable
    private data class CreateResponseDto(
        val success: Boolean = false,
        val responseObject: MakerDto? = null,
        val statusCode: Int = 0,
        val errorCode: String? = null,
    )

    @Serializable
    private data class MakerDto(
        val hashedOnchainId: String? = null,
    )

    private companion object {
        const val PATH_VALIDATE = "/v2/makers/validate"
        const val PATH_CREATE = "/v2/makers/create"
        const val FIELD_IS_VALID = "isValid"
        const val ERROR_CODE_PROFILE_NOT_FOUND = "profile_not_found"
    }
}
