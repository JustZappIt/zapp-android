// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.jce.ECNamedCurveTable
import java.math.BigInteger

private val curve = ECNamedCurveTable.getParameterSpec("secp256k1")
private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
private val halfN: BigInteger = curve.n.shiftRight(1)

fun EcdsaSigner.recoverPublicKey(
    recId: Int,
    r: BigInteger,
    s: BigInteger,
    messageHash: ByteArray,
) = recoverPublicKeyBytes(recId, r, s, messageHash)?.let(curve.curve::decodePoint)

internal actual fun secpSignRecoverable(messageHash: ByteArray, privateKey: ByteArray): ByteArray {
    val privateScalar = BigInteger(1, privateKey)
    val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
    signer.init(true, ECPrivateKeyParameters(privateScalar, domain))
    val generated = signer.generateSignature(messageHash)
    val r = generated[0]
    val s = if (generated[1] > halfN) curve.n.subtract(generated[1]) else generated[1]
    val expected =
        curve.g
            .multiply(privateScalar)
            .normalize()
            .getEncoded(false)
    val compact = r.toFieldBytes() + s.toFieldBytes()
    for (recId in 0..1) {
        if (secpRecoverPublicKey(messageHash, compact, recId)?.contentEquals(expected) == true) {
            return compact + recId.toByte()
        }
    }
    error("Failed to derive recovery ID for valid signature")
}

internal actual fun secpRecoverPublicKey(messageHash: ByteArray, signature: ByteArray, recId: Int): ByteArray? {
    if (signature.size != COMPACT_SIGNATURE_LEN) return null
    val r = BigInteger(1, signature.copyOfRange(0, FIELD_BYTES))
    val s = BigInteger(1, signature.copyOfRange(FIELD_BYTES, COMPACT_SIGNATURE_LEN))
    if (r.signum() <= 0 || s.signum() <= 0 || r >= curve.n || s >= curve.n) return null
    if (r >= curve.curve.field.characteristic) return null
    val compressed = byteArrayOf((COMPRESSED_EVEN_PREFIX + recId).toByte()) + r.toFieldBytes()
    val rPoint = runCatching { curve.curve.decodePoint(compressed) }.getOrNull() ?: return null
    val e = BigInteger(1, messageHash)
    val q =
        rPoint
            .multiply(s)
            .add(curve.g.multiply(curve.n.subtract(e.mod(curve.n))))
            .multiply(r.modInverse(curve.n))
            .normalize()
    return if (q.isInfinity) null else q.getEncoded(false)
}

internal actual fun secpPublicKeyUncompressed(privateKey: ByteArray): ByteArray =
    curve.g
        .multiply(BigInteger(1, privateKey))
        .normalize()
        .getEncoded(false)

internal actual fun secpNormalizePublicKeyUncompressed(publicKey: ByteArray): ByteArray =
    curve.curve
        .decodePoint(publicKey)
        .normalize()
        .getEncoded(false)

internal actual fun secpEcdh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    val point =
        curve.curve
            .decodePoint(publicKey)
            .multiply(BigInteger(1, privateKey))
            .normalize()
    return point.affineXCoord.encoded
}

private fun BigInteger.toFieldBytes(): ByteArray {
    val bytes = toByteArray()
    val unsigned = if (bytes.size > FIELD_BYTES) bytes.copyOfRange(bytes.size - FIELD_BYTES, bytes.size) else bytes
    return ByteArray(FIELD_BYTES - unsigned.size) + unsigned
}

private const val FIELD_BYTES = 32
private const val COMPACT_SIGNATURE_LEN = 64
private const val COMPRESSED_EVEN_PREFIX = 0x02
