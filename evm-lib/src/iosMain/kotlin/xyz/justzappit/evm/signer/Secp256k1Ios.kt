// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import fr.acinq.secp256k1.Secp256k1

internal actual fun secpSignRecoverable(messageHash: ByteArray, privateKey: ByteArray): ByteArray {
    val compact = Secp256k1.sign(messageHash, privateKey)
    val expected = Secp256k1.pubkeyCreate(privateKey)
    for (recId in 0..1) {
        val recovered = runCatching { Secp256k1.ecdsaRecover(compact, messageHash, recId) }.getOrNull()
        if (recovered?.contentEquals(expected) == true) return compact + recId.toByte()
    }
    error("Failed to derive recovery ID for valid signature")
}

internal actual fun secpRecoverPublicKey(messageHash: ByteArray, signature: ByteArray, recId: Int): ByteArray? =
    runCatching { Secp256k1.ecdsaRecover(signature, messageHash, recId) }.getOrNull()

internal actual fun secpPublicKeyUncompressed(privateKey: ByteArray): ByteArray = Secp256k1.pubkeyCreate(privateKey)

internal actual fun secpNormalizePublicKeyUncompressed(publicKey: ByteArray): ByteArray =
    Secp256k1.pubKeyTweakMul(publicKey, BYTE_ONE_SCALAR)

internal actual fun secpEcdh(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
    Secp256k1.pubKeyTweakMul(publicKey, privateKey).copyOfRange(1, 33)

private val BYTE_ONE_SCALAR = ByteArray(32).also { it[31] = 1 }
