// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.util.padLeftToWord

data class EcdsaSignature(
    val r: BigInteger,
    val s: BigInteger,
    val yParity: Byte,
)

object EcdsaSigner {
    fun sign(messageHash: ByteArray, privateKey: BigInteger): EcdsaSignature {
        require(messageHash.size == HASH_LEN) { "messageHash must be $HASH_LEN bytes" }
        require(privateKey > bigIntegerZero && privateKey < SECP256K1_N) { "private key out of range" }
        val compact = secpSignRecoverable(messageHash, privateKey.toUnsignedFieldBytes())
        check(compact.size == RECOVERABLE_SIGNATURE_LEN) { "invalid platform signature length" }
        return EcdsaSignature(
            r = BigInteger(1, compact.copyOfRange(0, FIELD_BYTES)),
            s = BigInteger(1, compact.copyOfRange(FIELD_BYTES, FIELD_BYTES * 2)),
            yParity = compact.last(),
        )
    }

    fun recoverPublicKeyBytes(
        recId: Int,
        r: BigInteger,
        s: BigInteger,
        messageHash: ByteArray,
    ): ByteArray? {
        require(recId == 0 || recId == 1) { "recId must be 0 or 1" }
        if (messageHash.size != HASH_LEN) return null
        if (r <= bigIntegerZero || s <= bigIntegerZero || r >= SECP256K1_N || s >= SECP256K1_N) return null
        return secpRecoverPublicKey(
            messageHash,
            r.toUnsignedFieldBytes() + s.toUnsignedFieldBytes(),
            recId,
        )
    }
}

internal expect fun secpSignRecoverable(messageHash: ByteArray, privateKey: ByteArray): ByteArray

internal expect fun secpRecoverPublicKey(messageHash: ByteArray, signature: ByteArray, recId: Int): ByteArray?

internal expect fun secpPublicKeyUncompressed(privateKey: ByteArray): ByteArray

internal expect fun secpNormalizePublicKeyUncompressed(publicKey: ByteArray): ByteArray

internal expect fun secpEcdh(privateKey: ByteArray, publicKey: ByteArray): ByteArray

internal fun BigInteger.toUnsignedFieldBytes(): ByteArray {
    val encoded = toByteArray()
    val unsigned = if (encoded.size > FIELD_BYTES) encoded.copyOfRange(encoded.size - FIELD_BYTES, encoded.size) else encoded
    return unsigned.padLeftToWord()
}

internal val SECP256K1_N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
private const val HASH_LEN = 32
private const val FIELD_BYTES = 32
private const val RECOVERABLE_SIGNATURE_LEN = 65
