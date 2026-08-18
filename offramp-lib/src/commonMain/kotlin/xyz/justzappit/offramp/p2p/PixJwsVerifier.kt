// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun interface PixSignatureTrustValidator {
    fun verify(
        algorithm: String,
        signingInput: ByteArray,
        signature: ByteArray,
        certificateChain: List<ByteArray>,
    ): Boolean
}

/**
 * Verifies the bank JWS according to the Banco Central do Brasil dynamic-PIX profile: the JWS
 * selects a key by `jku` + `kid`, the JWKS supplies its X.509 chain, and the payload is parsed only
 * after platform trust evaluation and signature verification succeed.
 */
internal class PixJwsVerifier(
    private val httpClient: HttpClient,
    private val trustValidator: PixSignatureTrustValidator =
        PixSignatureTrustValidator { algorithm, signingInput, signature, certificateChain ->
            platformVerifyTrustedPixSignature(algorithm, signingInput, signature, certificateChain)
        },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verifyAndExtractAmount(token: String, issuerUrl: String): String? {
        val jws = PixJws.parse(token)
        val jwksUrl = requireIssuerJwksUrl(jws.jku, issuerUrl)
        val response = httpClient.get(jwksUrl) { accept(ContentType.Application.Json) }
        if (!response.status.isSuccess()) {
            throw PixFetchException("PIX issuer JWKS responded ${response.status}")
        }
        requireIssuerJwksUrl(
            response.call.request.url
                .toString(),
            issuerUrl
        )
        rejectOversizedResponse(response.headers[HttpHeaders.ContentLength])
        val jwksBody = readBoundedBody(response.bodyAsChannel())
        val jwk = selectJwk(jwksBody, jws.keyId)
        validateJwkMetadata(jwk, jws)
        val certificateChain = decodeCertificateChain(jwk)
        validateThumbprints(jwk, jws, certificateChain.first())
        val valid =
            runCatching {
                trustValidator.verify(jws.algorithm, jws.signingInput, jws.signature, certificateChain)
            }.getOrDefault(false)
        if (!valid) throw PixFetchException("invalid or untrusted dynamic PIX JWS signature")
        return jws.extractAmount()
    }

    private fun selectJwk(body: String, keyId: String): JsonObject {
        val root =
            runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw PixFetchException("unparseable PIX issuer JWKS")
        val keys = root[FIELD_KEYS] as? JsonArray ?: throw PixFetchException("PIX issuer JWKS has no keys")
        val matches =
            keys
                .mapNotNull { it as? JsonObject }
                .filter { it.string(FIELD_KEY_ID) == keyId }
        if (matches.size != 1) throw PixFetchException("PIX issuer key id is missing or ambiguous")
        return matches.single()
    }

    private fun validateJwkMetadata(jwk: JsonObject, jws: PixJws) {
        val keyAlgorithm = jwk.string(FIELD_ALGORITHM)
        if (keyAlgorithm != null && keyAlgorithm != jws.algorithm) {
            throw PixFetchException("PIX issuer key algorithm mismatch")
        }
        val expectedKeyType =
            when {
                jws.algorithm in RSA_ALGORITHMS -> KEY_TYPE_RSA
                jws.algorithm in EC_ALGORITHMS -> KEY_TYPE_EC
                else -> throw PixFetchException("unsupported dynamic PIX JWS algorithm")
            }
        if (jwk.string(FIELD_KEY_TYPE) != expectedKeyType) {
            throw PixFetchException("PIX issuer key type mismatch")
        }
        val operations = (jwk[FIELD_KEY_OPERATIONS] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (operations == null || OPERATION_VERIFY !in operations) {
            throw PixFetchException("PIX issuer key is not authorized for verification")
        }
        val use = jwk.string(FIELD_USE)
        if (use != null && use != USE_SIGNATURE) throw PixFetchException("PIX issuer key is not a signing key")
    }

    private fun decodeCertificateChain(jwk: JsonObject): List<ByteArray> {
        val encoded =
            (jwk[FIELD_CERTIFICATE_CHAIN] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw PixFetchException("PIX issuer certificate chain is missing")
        if (encoded.isEmpty() || encoded.size > MAX_CERTIFICATE_CHAIN_LENGTH) {
            throw PixFetchException("invalid PIX issuer certificate chain length")
        }
        var totalBytes = 0
        return encoded.map { certificate ->
            val decoded =
                runCatching { decodeBase64(certificate) }
                    .getOrElse { throw PixFetchException("invalid PIX issuer certificate") }
            totalBytes += decoded.size
            if (decoded.isEmpty() || decoded.size > MAX_CERTIFICATE_BYTES || totalBytes > MAX_CERTIFICATE_CHAIN_BYTES) {
                throw PixFetchException("PIX issuer certificate chain is oversized")
            }
            decoded
        }
    }

    @OptIn(DelicateCryptographyApi::class)
    private fun validateThumbprints(jwk: JsonObject, jws: PixJws, leafCertificate: ByteArray) {
        jws.sha256Thumbprint?.let { expected ->
            val jwkThumbprint =
                jwk.string(FIELD_SHA256_THUMBPRINT)
                    ?: throw PixFetchException("PIX issuer SHA-256 certificate thumbprint is missing")
            if (!constantTimeEquals(expected, jwkThumbprint)) {
                throw PixFetchException("PIX issuer SHA-256 certificate thumbprint mismatch")
            }
            val actual =
                CryptographyProvider.Default
                    .get(SHA256)
                    .hasher()
                    .hashBlocking(leafCertificate)
                    .encodeBase64Url()
            if (!constantTimeEquals(expected, actual)) {
                throw PixFetchException("PIX JWS SHA-256 certificate thumbprint mismatch")
            }
        }
        jws.sha1Thumbprint?.let { expected ->
            val jwkThumbprint =
                jwk.string(FIELD_SHA1_THUMBPRINT)
                    ?: throw PixFetchException("PIX issuer SHA-1 certificate thumbprint is missing")
            if (!constantTimeEquals(expected, jwkThumbprint)) {
                throw PixFetchException("PIX issuer SHA-1 certificate thumbprint mismatch")
            }
            val actual =
                CryptographyProvider.Default
                    .get(SHA1)
                    .hasher()
                    .hashBlocking(leafCertificate)
                    .encodeBase64Url()
            if (!constantTimeEquals(expected, actual)) {
                throw PixFetchException("PIX JWS SHA-1 certificate thumbprint mismatch")
            }
        }
    }

    private fun JsonObject.string(field: String): String? =
        (get(field) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        val RSA_ALGORITHMS = setOf("RS256", "RS384", "RS512")
        val EC_ALGORITHMS = setOf("ES256", "ES384", "ES512")
        const val FIELD_KEYS = "keys"
        const val FIELD_KEY_ID = "kid"
        const val FIELD_ALGORITHM = "alg"
        const val FIELD_KEY_TYPE = "kty"
        const val FIELD_KEY_OPERATIONS = "key_ops"
        const val FIELD_USE = "use"
        const val FIELD_CERTIFICATE_CHAIN = "x5c"
        const val FIELD_SHA1_THUMBPRINT = "x5t"
        const val FIELD_SHA256_THUMBPRINT = "x5t#S256"
        const val KEY_TYPE_RSA = "RSA"
        const val KEY_TYPE_EC = "EC"
        const val OPERATION_VERIFY = "verify"
        const val USE_SIGNATURE = "sig"
        const val MAX_CERTIFICATE_CHAIN_LENGTH = 8
        const val MAX_CERTIFICATE_BYTES = 16 * 1024
        const val MAX_CERTIFICATE_CHAIN_BYTES = 64 * 1024
    }
}

private fun requireIssuerJwksUrl(raw: String, issuerUrl: String): String {
    val safeJwks = requireSafePixUrl(raw)
    val safeIssuer = requireSafePixUrl(issuerUrl)
    if (!Url(safeJwks).host.equals(Url(safeIssuer).host, ignoreCase = true)) {
        throw PixFetchException("PIX issuer JWKS must use the location host")
    }
    return safeJwks
}

private fun rejectOversizedResponse(contentLength: String?) {
    val declaredSize = contentLength?.toLongOrNull()
    if (declaredSize != null && declaredSize > MAX_PIX_RESPONSE_BYTES) {
        throw PixFetchException("PIX response exceeds $MAX_PIX_RESPONSE_BYTES bytes")
    }
}

private fun constantTimeEquals(left: String, right: String): Boolean {
    var difference = left.length xor right.length
    val length = maxOf(left.length, right.length)
    repeat(length) { index ->
        difference = difference or (left.getOrElse(index) { '\u0000' }.code xor right.getOrElse(index) { '\u0000' }.code)
    }
    return difference == 0
}

private fun ByteArray.encodeBase64Url(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val output = StringBuilder((size * 4 + 2) / 3)
    var index = 0
    while (index < size) {
        val first = this[index++].toInt() and 0xff
        val second = if (index < size) this[index++].toInt() and 0xff else -1
        val third = if (index < size) this[index++].toInt() and 0xff else -1
        output.append(alphabet[first ushr 2])
        output.append(alphabet[((first and 0x03) shl 4) or (if (second >= 0) second ushr 4 else 0)])
        if (second >= 0) output.append(alphabet[((second and 0x0f) shl 2) or (if (third >= 0) third ushr 6 else 0)])
        if (third >= 0) output.append(alphabet[third and 0x3f])
    }
    return output.toString()
}

internal fun ByteArray.joseEcdsaToDer(): ByteArray {
    require(size % 2 == 0 && isNotEmpty())
    val componentLength = size / 2
    val r = copyOfRange(0, componentLength).derInteger()
    val s = copyOfRange(componentLength, size).derInteger()
    val payload = byteArrayOf(DER_INTEGER) + r.size.derLength() + r + byteArrayOf(DER_INTEGER) + s.size.derLength() + s
    return byteArrayOf(DER_SEQUENCE) + payload.size.derLength() + payload
}

private fun ByteArray.derInteger(): ByteArray {
    val firstNonZero = indexOfFirst { it != 0.toByte() }.let { if (it == -1) lastIndex else it }
    val value = copyOfRange(firstNonZero, size)
    return if (value.first().toInt() and HIGH_BIT != 0) byteArrayOf(0) + value else value
}

private fun Int.derLength(): ByteArray =
    if (this < DER_LONG_FORM_THRESHOLD) {
        byteArrayOf(toByte())
    } else {
        byteArrayOf(DER_ONE_LENGTH_BYTE, toByte())
    }

internal expect fun platformVerifyTrustedPixSignature(
    algorithm: String,
    signingInput: ByteArray,
    signature: ByteArray,
    certificateChain: List<ByteArray>,
): Boolean

private const val HIGH_BIT = 0x80
private const val DER_LONG_FORM_THRESHOLD = 0x80
private const val DER_ONE_LENGTH_BYTE: Byte = -127
private const val DER_SEQUENCE: Byte = 0x30
private const val DER_INTEGER: Byte = 0x02
