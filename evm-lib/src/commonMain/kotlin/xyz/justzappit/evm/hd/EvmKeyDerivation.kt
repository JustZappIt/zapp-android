// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.signer.EcdsaSignature
import xyz.justzappit.evm.signer.EcdsaSigner
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.signer.secpPublicKeyUncompressed
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.padLeftToWord

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

@Suppress("TooManyFunctions")
object EvmKeyDerivation {
    private const val HARDENED_BIT: Int = 0x80000000.toInt()
    private const val PBKDF2_ITERATIONS = 2048
    private const val SEED_BYTES = 64
    private const val FIELD_BYTES = 32
    private const val ADDRESS_BYTES = 20

    fun derive(mnemonic: CharArray, accountIndex: Int = 0, passphrase: String = ""): EvmKey {
        require(accountIndex >= 0) { "accountIndex must be non-negative" }
        val seed = mnemonicToSeed(mnemonic, passphrase)
        var current: ExtKey? = null
        return try {
            current = masterFromSeed(seed)
            listOf(
                44 or HARDENED_BIT,
                60 or HARDENED_BIT,
                0 or HARDENED_BIT,
                0,
                accountIndex,
            ).forEach { index ->
                val parent = checkNotNull(current)
                current = ckdPrivWithRetry(parent, index)
                parent.zeroize()
            }
            fromPrivateKey(checkNotNull(current).priv)
        } finally {
            seed.fill(0)
            current?.zeroize()
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

    private data class ExtKey(
        val priv: ByteArray,
        val chainCode: ByteArray,
    ) {
        fun zeroize() {
            priv.fill(0)
            chainCode.fill(0)
        }
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

    private fun masterFromSeed(seed: ByteArray): ExtKey {
        val key = "Bitcoin seed".encodeToByteArray()
        val derived = platformHmacSha512(key, seed)
        return try {
            ExtKey(
                priv = derived.copyOfRange(0, FIELD_BYTES),
                chainCode = derived.copyOfRange(FIELD_BYTES, derived.size),
            )
        } finally {
            key.fill(0)
            derived.fill(0)
        }
    }

    private fun ckdPrivWithRetry(parent: ExtKey, startIndex: Int): ExtKey {
        var index = startIndex
        while (true) {
            val candidate = ckdPrivOnce(parent, index)
            if (candidate != null) return candidate
            val next = index + 1
            check(next != startIndex) { "BIP-32 ckdPriv: exhausted all 2^32 child indices" }
            index = next
        }
    }

    private fun ckdPrivOnce(parent: ExtKey, index: Int): ExtKey? {
        val hardened = (index.toLong() and UNSIGNED_INT_MASK) >= HARDENED_THRESHOLD
        val data =
            if (hardened) {
                byteArrayOf(0x00) + parent.priv + intToBytes(index)
            } else {
                compressedPub(parent.priv) + intToBytes(index)
            }
        val derived = platformHmacSha512(parent.chainCode, data)
        val left = derived.copyOfRange(0, FIELD_BYTES)
        return try {
            val leftNumber = BigInteger(1, left)
            if (leftNumber >= SECP256K1_N) return null
            val child = (leftNumber + BigInteger(1, parent.priv)).mod(SECP256K1_N)
            if (child == bigIntegerZero) return null
            ExtKey(
                priv = child.toByteArray().padLeftToWord(),
                chainCode = derived.copyOfRange(FIELD_BYTES, derived.size),
            )
        } finally {
            data.fill(0)
            derived.fill(0)
            left.fill(0)
        }
    }

    private fun compressedPub(privBytes: ByteArray): ByteArray {
        val uncompressed = secpPublicKeyUncompressed(privBytes)
        val prefix = if (uncompressed.last().toInt() and 1 == 0) COMPRESSED_EVEN_PREFIX else COMPRESSED_ODD_PREFIX
        return byteArrayOf(prefix.toByte()) + uncompressed.copyOfRange(1, FIELD_BYTES + 1)
    }

    private fun addressFromPub(pubXY: ByteArray): Address {
        val hash = keccak256(pubXY)
        return Address.fromBytes(hash.copyOfRange(hash.size - ADDRESS_BYTES, hash.size))
    }

    private fun intToBytes(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24 and BYTE_MASK).toByte(),
            (value ushr 16 and BYTE_MASK).toByte(),
            (value ushr 8 and BYTE_MASK).toByte(),
            (value and BYTE_MASK).toByte(),
        )

    private const val UNSIGNED_INT_MASK = 0xffff_ffffL
    private const val HARDENED_THRESHOLD = 0x8000_0000L
    private const val UNCOMPRESSED_PUBLIC_KEY_BYTES = 65
    private const val UNCOMPRESSED_PREFIX = 0x04
    private const val COMPRESSED_EVEN_PREFIX = 0x02
    private const val COMPRESSED_ODD_PREFIX = 0x03
    private const val BYTE_MASK = 0xff
}

internal expect fun platformNormalizeNfkd(value: String): String

internal expect fun platformPbkdf2Sha512(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    outputBytes: Int,
): ByteArray

internal expect fun platformHmacSha512(key: ByteArray, data: ByteArray): ByteArray
