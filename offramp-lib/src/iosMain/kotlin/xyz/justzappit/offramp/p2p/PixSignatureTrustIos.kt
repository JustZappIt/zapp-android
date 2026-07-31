// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecKeyAlgorithm
import platform.Security.SecKeyIsAlgorithmSupported
import platform.Security.SecKeyVerifySignature
import platform.Security.SecPolicyCreateBasicX509
import platform.Security.SecTrustCopyKey
import platform.Security.SecTrustCreateWithCertificates
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRefVar
import platform.Security.SecTrustSetNetworkFetchAllowed
import platform.Security.errSecSuccess
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA256
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA384
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA512
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA384
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA512
import platform.Security.kSecKeyOperationTypeVerify

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformVerifyTrustedPixSignature(
    algorithm: String,
    signingInput: ByteArray,
    signature: ByteArray,
    certificateChain: List<ByteArray>,
): Boolean =
    runCatching {
        memScoped {
            val certificates =
                certificateChain.map { encoded ->
                    encoded.useNSData { data ->
                        data.useCFData { cfData ->
                            checkNotNull(SecCertificateCreateWithData(null, cfData))
                        }
                    }
                }
            try {
                val certificatePointers = allocArray<COpaquePointerVar>(certificates.size)
                certificates.forEachIndexed { index, certificate ->
                    certificatePointers[index] = certificate.reinterpret<ByteVar>()
                }
                val certificateArray =
                    checkNotNull(CFArrayCreate(null, certificatePointers, certificates.size.convert(), null))
                try {
                    val policy = checkNotNull(SecPolicyCreateBasicX509())
                    try {
                        val trustRef = alloc<SecTrustRefVar>()
                        trustRef.value = null
                        check(SecTrustCreateWithCertificates(certificateArray, policy, trustRef.ptr) == errSecSuccess)
                        val trust = checkNotNull(trustRef.value)
                        try {
                            check(SecTrustSetNetworkFetchAllowed(trust, false) == errSecSuccess)
                            val trustError = alloc<CFErrorRefVar>()
                            trustError.value = null
                            if (!SecTrustEvaluateWithError(trust, trustError.ptr)) {
                                trustError.value.release()
                                return@memScoped false
                            }
                            val publicKey = checkNotNull(SecTrustCopyKey(trust))
                            try {
                                val secAlgorithm = algorithm.secKeyAlgorithm()
                                if (!SecKeyIsAlgorithmSupported(publicKey, kSecKeyOperationTypeVerify, secAlgorithm)) {
                                    return@memScoped false
                                }
                                signingInput.useNSData { inputData ->
                                    signature.useNSData { signatureData ->
                                        inputData.useCFData { cfInput ->
                                            signatureData.useCFData { cfSignature ->
                                                val verifyError = alloc<CFErrorRefVar>()
                                                verifyError.value = null
                                                val valid =
                                                    SecKeyVerifySignature(
                                                        publicKey,
                                                        secAlgorithm,
                                                        cfInput,
                                                        cfSignature,
                                                        verifyError.ptr,
                                                    )
                                                verifyError.value.release()
                                                valid
                                            }
                                        }
                                    }
                                }
                            } finally {
                                publicKey.release()
                            }
                        } finally {
                            trust.release()
                        }
                    } finally {
                        policy.release()
                    }
                } finally {
                    certificateArray.release()
                }
            } finally {
                certificates.forEach { it.release() }
            }
        }
    }.getOrDefault(false)

@OptIn(ExperimentalForeignApi::class)
private fun String.secKeyAlgorithm(): SecKeyAlgorithm =
    checkNotNull(
        when (this) {
            "RS256" -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256
            "RS384" -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA384
            "RS512" -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA512
            "ES256" -> kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA256
            "ES384" -> kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA384
            "ES512" -> kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA512
            else -> throw IllegalArgumentException("unsupported PIX JWS algorithm")
        },
    )

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private inline fun <T> ByteArray.useNSData(block: (NSData) -> T): T =
    usePinned { pinned -> block(NSData.create(bytes = pinned.addressOf(0), length = size.toULong())) }

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> NSData.useCFData(block: (CFDataRef) -> T): T {
    @Suppress("UNCHECKED_CAST")
    val retained = CFBridgingRetain(this) as CFDataRef
    try {
        return block(retained)
    } finally {
        CFBridgingRelease(retained)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFTypeRef?.release() {
    if (this != null) CFRelease(this)
}
