// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.signer.EcdsaSigner
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.signer.toUnsignedFieldBytes
import xyz.justzappit.evm.types.Address

/**
 * The `erc191` intents standard: `personal_sign` over the payload, sent as `secp256k1:` + Base58(r ‖ s ‖ v).
 * Internal so that only [IntentsLogin] and [IntentTransferSigner] sign with the private-account key.
 */
internal object Erc191IntentSigner {
    const val STANDARD = "erc191"
    private const val PREFIX = "secp256k1:"
    private const val EIP191_BYTE: Byte = 0x19
    private const val EIP191_HEADER = "Ethereum Signed Message:\n"
    private const val SIGNATURE_BYTES = 65
    private const val FIELD_BYTES = 32

    // 65 bytes are at most 89 Base58 characters; longer input is refused before the quadratic decode.
    private const val MAX_ENCODED_LENGTH = 90
    private val HALF_N = SECP256K1_N.shiftRight(1)

    fun sign(key: EvmKey, payload: String): String {
        val signature = key.signRecoverable(personalMessageHash(payload))
        val bytes =
            signature.r.toUnsignedFieldBytes() +
                signature.s.toUnsignedFieldBytes() +
                byteArrayOf(signature.yParity)
        return PREFIX + Base58.encode(bytes)
    }

    fun personalMessageHash(payload: String): ByteArray {
        val bytes = payload.encodeToByteArray()
        return keccak256(byteArrayOf(EIP191_BYTE) + "$EIP191_HEADER${bytes.size}".encodeToByteArray() + bytes)
    }

    /** For tests: who signed [payload], or null for a malformed or high-s signature. */
    fun recoverSigner(payload: String, signature: String): Address? {
        val raw =
            signature
                .takeIf { it.startsWith(PREFIX) && it.length - PREFIX.length <= MAX_ENCODED_LENGTH }
                ?.let { runCatching { Base58.decode(it.removePrefix(PREFIX)) }.getOrNull() }
                ?.takeIf { it.size == SIGNATURE_BYTES && (it.last() == 0.toByte() || it.last() == 1.toByte()) }
        val s = raw?.let { BigInteger(1, it.copyOfRange(FIELD_BYTES, FIELD_BYTES * 2)) }
        val publicKey =
            if (raw == null || s == null || s > HALF_N) {
                null
            } else {
                EcdsaSigner.recoverPublicKeyBytes(
                    recId = raw.last().toInt(),
                    r = BigInteger(1, raw.copyOfRange(0, FIELD_BYTES)),
                    s = s,
                    messageHash = personalMessageHash(payload),
                )
            }
        return publicKey?.let { pub ->
            val xy = if (pub.size == FIELD_BYTES * 2 + 1) pub.copyOfRange(1, pub.size) else pub
            val hash = keccak256(xy)
            Address.fromBytes(hash.copyOfRange(hash.size - Address.LEN_BYTES, hash.size))
        }
    }
}
