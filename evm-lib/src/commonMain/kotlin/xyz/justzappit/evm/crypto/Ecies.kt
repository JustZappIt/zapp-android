// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.random.CryptographyRandom
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.signer.secpEcdh
import xyz.justzappit.evm.signer.secpNormalizePublicKeyUncompressed
import xyz.justzappit.evm.signer.secpPublicKeyUncompressed
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex

data class Encrypted(
    val iv: String,
    val ephemPublicKey: String,
    val ciphertext: String,
    val mac: String,
)

@OptIn(DelicateCryptographyApi::class)
object Ecies {
    private const val MIN_CIPHER_BYTES = 82
    private const val IV_BYTES = 16
    private const val COMPRESSED_PUBKEY_BYTES = 33
    private const val MAC_BYTES = 32
    private const val FIELD_BYTES = 32

    fun encryptWithPublicKey(publicKeyHex: String, message: String): Encrypted {
        val pubBytes = ("04$publicKeyHex").hexToBytes()

        val ephemPriv = generateScalar()
        val ephemPrivBytes = ephemPriv.toFieldBytes()
        val ephemPubUncompressed = secpPublicKeyUncompressed(ephemPrivBytes)
        val sharedSecret = secpEcdh(ephemPrivBytes, pubBytes)
        val (encKey, macKey) = deriveKeys(sharedSecret)

        val iv = CryptographyRandom.Default.nextBytes(IV_BYTES)
        val plaintext = message.encodeToByteArray()
        val ciphertext = aesCbc(encKey).encryptWithIvBlocking(iv, plaintext)

        val mac = hmacSha256(macKey, iv + ephemPubUncompressed + ciphertext)

        return Encrypted(
            iv = iv.toHex(),
            ephemPublicKey = ephemPubUncompressed.toHex(),
            ciphertext = ciphertext.toHex(),
            mac = mac.toHex(),
        )
    }

    fun decryptWithPrivateKey(privateKeyHex: String, encrypted: Encrypted): String {
        val privBytes = privateKeyHex.removePrefix("0x").hexToBytes()
        val ephemPubBytes = encrypted.ephemPublicKey.hexToBytes()
        val iv = encrypted.iv.hexToBytes()
        val ciphertext = encrypted.ciphertext.hexToBytes()
        val macBytes = encrypted.mac.hexToBytes()

        val sharedSecret = secpEcdh(privBytes, ephemPubBytes)
        val (encKey, macKey) = deriveKeys(sharedSecret)

        val computedMac = hmacSha256(macKey, iv + ephemPubBytes + ciphertext)
        check(constantTimeEquals(computedMac, macBytes)) {
            "MAC mismatch — ciphertext may be corrupted or tampered with"
        }

        val plaintext = aesCbc(encKey).decryptWithIvBlocking(iv, ciphertext)
        return plaintext.decodeToString()
    }

    fun cipherStringify(encrypted: Encrypted): String {
        val uncompressed = secpNormalizePublicKeyUncompressed(encrypted.ephemPublicKey.hexToBytes())
        val compressed = compressPublicKey(uncompressed)
        val out =
            encrypted.iv.hexToBytes() +
                compressed +
                encrypted.mac.hexToBytes() +
                encrypted.ciphertext.hexToBytes()
        return out.toHex()
    }

    fun cipherParse(s: String): Encrypted {
        val buf = s.hexToBytes()
        require(buf.size >= MIN_CIPHER_BYTES) {
            "cipherParse: input too short (${buf.size} bytes, need at least $MIN_CIPHER_BYTES)"
        }
        val ivEnd = IV_BYTES
        val compEnd = ivEnd + COMPRESSED_PUBKEY_BYTES
        val macEnd = compEnd + MAC_BYTES

        val iv = buf.copyOfRange(0, ivEnd)
        val compressed = buf.copyOfRange(ivEnd, compEnd)
        val mac = buf.copyOfRange(compEnd, macEnd)
        val ciphertext = buf.copyOfRange(macEnd, buf.size)

        val uncompressed = secpNormalizePublicKeyUncompressed(compressed)

        return Encrypted(
            iv = iv.toHex(),
            ephemPublicKey = uncompressed.toHex(),
            ciphertext = ciphertext.toHex(),
            mac = mac.toHex(),
        )
    }

    private fun generateScalar(): BigInteger {
        while (true) {
            val bytes = CryptographyRandom.Default.nextBytes(FIELD_BYTES)
            val k = BigInteger(1, bytes)
            if (k > bigIntegerZero && k < SECP256K1_N) return k
        }
    }

    /**
     * Key derivation deliberately uses plain SHA-512(sharedSecret) instead of HKDF, matching the
     * [eth-crypto](https://github.com/pubkey/eth-crypto/blob/master/src/encrypt-with-public-key.ts)
     * convention that p2p.me's relays interoperate with. Replacing this with NIST SP 800-56 / SEC1
     * standard ECIES (HKDF + info string) will break wire-format compatibility with every existing
     * counterparty — do NOT "fix" this without coordinating a hard fork of the relay protocol.
     */
    private fun deriveKeys(sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val hash =
            CryptographyProvider.Default
                .get(SHA512)
                .hasher()
                .hashBlocking(sharedSecret)
        return hash.copyOfRange(0, FIELD_BYTES) to hash.copyOfRange(FIELD_BYTES, hash.size)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        CryptographyProvider.Default
            .get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignatureBlocking(data)

    private fun aesCbc(key: ByteArray) =
        CryptographyProvider.Default
            .get(AES.CBC)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher(padding = true)

    private fun compressPublicKey(uncompressed: ByteArray): ByteArray {
        require(uncompressed.size == UNCOMPRESSED_PUBKEY_BYTES && uncompressed[0] == UNCOMPRESSED_PREFIX)
        val prefix = if (uncompressed.last().toInt() and 1 == 0) COMPRESSED_EVEN_PREFIX else COMPRESSED_ODD_PREFIX
        return byteArrayOf(prefix.toByte()) + uncompressed.copyOfRange(1, FIELD_BYTES + 1)
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        left.indices.forEach { index -> difference = difference or (left[index].toInt() xor right[index].toInt()) }
        return difference == 0
    }

    private fun BigInteger.toFieldBytes(): ByteArray {
        val bytes = toByteArray()
        val unsigned = if (bytes.size > FIELD_BYTES) bytes.copyOfRange(bytes.size - FIELD_BYTES, bytes.size) else bytes
        return ByteArray(FIELD_BYTES - unsigned.size) + unsigned
    }

    private const val UNCOMPRESSED_PUBKEY_BYTES = 65
    private const val UNCOMPRESSED_PREFIX: Byte = 0x04
    private const val COMPRESSED_EVEN_PREFIX = 0x02
    private const val COMPRESSED_ODD_PREFIX = 0x03
}
