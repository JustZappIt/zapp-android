// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.Bip32.HARDENED
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.signer.EcdsaSignature
import xyz.justzappit.evm.signer.EcdsaSigner
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.signer.secpPublicKeyUncompressed
import xyz.justzappit.evm.types.Address

class EvmKey internal constructor(
    internal val privateKey: ByteArray,
    val publicKey: ByteArray,
    val address: Address,
) {
    fun signRecoverable(messageHash: ByteArray): EcdsaSignature =
        EcdsaSigner.sign(messageHash, BigInteger(1, privateKey))

    fun exportPrivateKeyBytes(): ByteArray = privateKey.copyOf()

    fun zeroize() {
        privateKey.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvmKey) return false
        return privateKey.contentEquals(other.privateKey) &&
            publicKey.contentEquals(other.publicKey) &&
            address == other.address
    }

    override fun hashCode(): Int = 31 * publicKey.contentHashCode() + address.hashCode()

    override fun toString(): String = "EvmKey(address=$address)"
}

object EvmKeyDerivation {
    private const val PBKDF2_ITERATIONS = 2048
    private const val SEED_BYTES = 64
    private const val FIELD_BYTES = 32
    private const val ADDRESS_BYTES = 20

    fun derive(mnemonic: CharArray, accountIndex: Int = 0, passphrase: String = ""): EvmKey {
        require(accountIndex >= 0) { "accountIndex must be non-negative" }
        val seed = mnemonicToSeed(mnemonic, passphrase)
        val key =
            try {
                Bip32.derive(
                    Bip32.master(seed),
                    listOf(44 or HARDENED, 60 or HARDENED, 0 or HARDENED, 0, accountIndex),
                )
            } finally {
                seed.fill(0)
            }
        return try {
            fromPrivateKey(key.privateKey)
        } finally {
            key.zeroize()
        }
    }

    fun derive(mnemonic: String, accountIndex: Int = 0, passphrase: String = ""): EvmKey {
        val chars = mnemonic.toCharArray()
        return try {
            derive(chars, accountIndex, passphrase)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun fromPrivateKey(privBytes: ByteArray): EvmKey {
        require(privBytes.size == FIELD_BYTES) { "private key must be 32 bytes" }
        val priv = BigInteger(1, privBytes)
        require(priv > bigIntegerZero && priv < SECP256K1_N) { "private key out of range" }
        val uncompressed = secpPublicKeyUncompressed(privBytes)
        check(uncompressed.size == UNCOMPRESSED_PUBLIC_KEY_BYTES && uncompressed[0] == UNCOMPRESSED_PREFIX.toByte())
        val pubXY = uncompressed.copyOfRange(1, uncompressed.size)
        return EvmKey(
            privateKey = privBytes.copyOf(),
            publicKey = pubXY,
            address = addressFromPub(pubXY),
        )
    }

    private fun mnemonicToSeed(mnemonic: CharArray, passphrase: String): ByteArray {
        val mnemonicString = mnemonic.concatToString().trim()
        val normalizedMnemonic = platformNormalizeNfkd(mnemonicString).encodeToByteArray()
        val normalizedSalt = platformNormalizeNfkd("mnemonic$passphrase").encodeToByteArray()
        return try {
            platformPbkdf2Sha512(normalizedMnemonic, normalizedSalt, PBKDF2_ITERATIONS, SEED_BYTES)
        } finally {
            normalizedMnemonic.fill(0)
            normalizedSalt.fill(0)
        }
    }

    private fun addressFromPub(pubXY: ByteArray): Address {
        val hash = keccak256(pubXY)
        return Address.fromBytes(hash.copyOfRange(hash.size - ADDRESS_BYTES, hash.size))
    }

    private const val UNCOMPRESSED_PUBLIC_KEY_BYTES = 65
    private const val UNCOMPRESSED_PREFIX = 0x04
}

internal expect fun platformNormalizeNfkd(value: String): String

internal expect fun platformPbkdf2Sha512(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    outputBytes: Int,
): ByteArray

internal expect fun platformHmacSha512(key: ByteArray, data: ByteArray): ByteArray
