// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal actual fun platformVerifyTrustedPixSignature(
    algorithm: String,
    signingInput: ByteArray,
    signature: ByteArray,
    certificateChain: List<ByteArray>,
): Boolean =
    runCatching {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates =
            certificateChain.map { encoded ->
                factory.generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
            }
        val leaf = certificates.first()
        certificates.forEach(X509Certificate::checkValidity)
        require(leaf.keyUsage?.getOrNull(DIGITAL_SIGNATURE_KEY_USAGE_INDEX) == true)

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val trustAnchors = trustManager.acceptedIssuers.map { TrustAnchor(it, null) }.toSet()
        val pathCertificates =
            if (certificates.size > 1 && certificates.last().isSelfIssued()) certificates.dropLast(1) else certificates
        val parameters = PKIXParameters(trustAnchors).apply { isRevocationEnabled = false }
        val path = factory.generateCertPath(pathCertificates)
        CertPathValidator.getInstance("PKIX").validate(path, parameters)

        val verifier = Signature.getInstance(algorithm.jvmSignatureAlgorithm())
        verifier.initVerify(leaf.publicKey)
        verifier.update(signingInput)
        verifier.verify(if (algorithm.startsWith("ES")) signature.joseEcdsaToDer() else signature)
    }.getOrDefault(false)

private fun X509Certificate.isSelfIssued(): Boolean = subjectX500Principal == issuerX500Principal

private fun String.jvmSignatureAlgorithm(): String =
    when (this) {
        "RS256" -> "SHA256withRSA"
        "RS384" -> "SHA384withRSA"
        "RS512" -> "SHA512withRSA"
        "ES256" -> "SHA256withECDSA"
        "ES384" -> "SHA384withECDSA"
        "ES512" -> "SHA512withECDSA"
        else -> throw IllegalArgumentException("unsupported PIX JWS algorithm")
    }

private const val DIGITAL_SIGNATURE_KEY_USAGE_INDEX = 0
