// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Parsed compact JWS returned by a dynamic PIX `location` endpoint. */
internal class PixJws private constructor(
    val algorithm: String,
    val jku: String,
    val keyId: String,
    val sha1Thumbprint: String?,
    val sha256Thumbprint: String?,
    private val encodedHeader: String,
    private val encodedPayload: String,
    val signature: ByteArray,
) {
    val signingInput: ByteArray
        get() = "$encodedHeader.$encodedPayload".encodeToByteArray()

    fun extractAmount(): String? {
        val payload = decodeJsonObject(encodedPayload) ?: throw PixFetchException("unparseable dynamic PIX payload")
        val valor = payload[FIELD_VALOR] as? JsonObject ?: return null
        return (valor[FIELD_ORIGINAL] as? JsonPrimitive)?.contentOrNull
    }

    companion object {
        fun parse(token: String): PixJws {
            val parts = token.trim().split('.')
            if (parts.size != JWT_PARTS || parts.any { it.isEmpty() }) {
                throw PixFetchException("unparseable dynamic PIX JWS")
            }
            val header = decodeJsonObject(parts[0]) ?: throw PixFetchException("unparseable dynamic PIX JWS header")
            val criticalHeaders = header[FIELD_CRITICAL] as? JsonArray
            if (criticalHeaders != null && criticalHeaders.isNotEmpty()) {
                throw PixFetchException("unsupported critical dynamic PIX JWS header")
            }
            val algorithm = header.requiredString(FIELD_ALGORITHM)
            if (algorithm.equals(ALGORITHM_NONE, ignoreCase = true)) {
                throw PixFetchException("unsigned dynamic PIX JWS is not accepted")
            }
            val sha1Thumbprint = header.optionalString(FIELD_SHA1_THUMBPRINT)
            val sha256Thumbprint = header.optionalString(FIELD_SHA256_THUMBPRINT)
            if (sha1Thumbprint == null && sha256Thumbprint == null) {
                throw PixFetchException("dynamic PIX JWS certificate thumbprint is missing")
            }
            return PixJws(
                algorithm = algorithm,
                jku = header.requiredString(FIELD_JKU),
                keyId = header.requiredString(FIELD_KEY_ID),
                sha1Thumbprint = sha1Thumbprint,
                sha256Thumbprint = sha256Thumbprint,
                encodedHeader = parts[0],
                encodedPayload = parts[1],
                signature = decodeBase64Url(parts[2]),
            )
        }

        private fun JsonObject.requiredString(field: String): String =
            optionalString(field) ?: throw PixFetchException("dynamic PIX JWS $field is missing")

        private fun JsonObject.optionalString(field: String): String? =
            (get(field) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private const val FIELD_VALOR = "valor"
        private const val FIELD_ORIGINAL = "original"
        private const val FIELD_ALGORITHM = "alg"
        private const val FIELD_CRITICAL = "crit"
        private const val FIELD_JKU = "jku"
        private const val FIELD_KEY_ID = "kid"
        private const val FIELD_SHA1_THUMBPRINT = "x5t"
        private const val FIELD_SHA256_THUMBPRINT = "x5t#S256"
        private const val ALGORITHM_NONE = "none"
        private const val JWT_PARTS = 3
    }
}

private val pixJson = Json { ignoreUnknownKeys = true }

private fun decodeJsonObject(value: String): JsonObject? =
    runCatching { pixJson.parseToJsonElement(decodeBase64Url(value).decodeToString()) as? JsonObject }.getOrNull()

internal fun decodeBase64Url(value: String): ByteArray {
    val normalized = value.replace('-', '+').replace('_', '/')
    val padded = normalized.padEnd((normalized.length + BASE64_GROUP - 1) / BASE64_GROUP * BASE64_GROUP, '=')
    return decodeBase64(padded)
}

internal fun decodeBase64(value: String): ByteArray {
    require(value.length % BASE64_GROUP == 0) { "invalid base64 length" }
    val padding = value.takeLastWhile { it == '=' }.length
    require(padding <= MAX_BASE64_PADDING) { "invalid base64 padding" }
    val output = ByteArray(value.length / BASE64_GROUP * BYTES_PER_BASE64_GROUP - padding)
    var outputIndex = 0
    value.chunked(BASE64_GROUP).forEachIndexed { groupIndex, group ->
        val isLast = groupIndex == value.length / BASE64_GROUP - 1
        require(isLast || '=' !in group) { "invalid base64 padding" }
        val a = group[0].base64Value()
        val b = group[1].base64Value()
        val c = if (group[2] == '=') 0 else group[2].base64Value()
        val d = if (group[3] == '=') 0 else group[3].base64Value()
        val bits = (a shl 18) or (b shl 12) or (c shl 6) or d
        if (outputIndex < output.size) output[outputIndex++] = (bits ushr 16).toByte()
        if (outputIndex < output.size) output[outputIndex++] = (bits ushr 8).toByte()
        if (outputIndex < output.size) output[outputIndex++] = bits.toByte()
    }
    return output
}

private fun Char.base64Value(): Int =
    when (this) {
        in 'A'..'Z' -> code - 'A'.code
        in 'a'..'z' -> code - 'a'.code + LOWERCASE_BASE64_OFFSET
        in '0'..'9' -> code - '0'.code + DIGIT_BASE64_OFFSET
        '+' -> PLUS_BASE64_VALUE
        '/' -> SLASH_BASE64_VALUE
        else -> throw IllegalArgumentException("invalid base64 character")
    }

/** A dynamic-PIX fetch or signature validation failed. */
class PixFetchException(
    message: String,
) : Exception(message)

private const val BASE64_GROUP = 4
private const val BYTES_PER_BASE64_GROUP = 3
private const val MAX_BASE64_PADDING = 2
private const val LOWERCASE_BASE64_OFFSET = 26
private const val DIGIT_BASE64_OFFSET = 52
private const val PLUS_BASE64_VALUE = 62
private const val SLASH_BASE64_VALUE = 63
