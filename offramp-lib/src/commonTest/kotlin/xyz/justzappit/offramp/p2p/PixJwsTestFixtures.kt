// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

internal object PixJwsTestFixtures {
    const val ISSUER_URL = "https://bank.test/v2/cobv/abc"
    const val JWKS_URL = "https://bank.test/.well-known/pix-jwks.json"
    const val KEY_ID = "pix-key-2026"
    val certificate = "fake-leaf-certificate".encodeToByteArray()
    val certificateBase64 = certificate.encodeBase64()
    val certificateThumbprint =
        CryptographyProvider.Default
            .get(SHA256)
            .hasher()
            .hashBlocking(certificate)
            .encodeBase64UrlForTest()

    fun token(
        payload: String = """{"valor":{"original":"123.45"}}""",
        algorithm: String = "RS256",
        jku: String = JWKS_URL,
        keyId: String = KEY_ID,
        thumbprint: String = certificateThumbprint,
        signature: ByteArray = byteArrayOf(1, 2, 3),
        extraHeader: String = "",
    ): String {
        val header =
            """{"alg":"$algorithm","jku":"$jku","kid":"$keyId","x5t#S256":"$thumbprint"$extraHeader}"""
                .encodeToByteArray()
                .encodeBase64UrlForTest()
        return "$header.${payload.encodeToByteArray().encodeBase64UrlForTest()}.${signature.encodeBase64UrlForTest()}"
    }

    fun jwks(
        algorithm: String = "RS256",
        keyId: String = KEY_ID,
        keyType: String = "RSA",
        keyOperations: String = "\"key_ops\":[\"verify\"],",
        thumbprintField: String = "\"x5t#S256\":\"$certificateThumbprint\",",
        keys: Int = 1,
    ): String {
        val key =
            """{"kid":"$keyId","alg":"$algorithm","kty":"$keyType",$keyOperations"use":"sig",$thumbprintField"x5c":["$certificateBase64"]}"""
        return """{"keys":[${List(keys) { key }.joinToString()}]}"""
    }
}

internal fun ByteArray.encodeBase64UrlForTest(): String =
    encodeBase64().replace('+', '-').replace('/', '_').trimEnd('=')
