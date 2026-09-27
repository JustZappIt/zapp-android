// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

/**
 * Bitcoin-alphabet Base58, the encoding NEAR Intents uses for `secp256k1:` and `ed25519:` signatures and
 * public keys. Leading zero bytes become leading '1's, as in every other Base58 implementation.
 */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BASE = 58
    private const val BYTE_RADIX = 256
    private const val BYTE_MASK = 0xff

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val leadingZeros = input.takeWhile { it == 0.toByte() }.size
        // Repeated division of the big-endian number by 58, one output digit per pass.
        val digits = IntArray(input.size * 2)
        var length = 0
        for (byte in input) {
            var carry = byte.toInt() and BYTE_MASK
            for (i in 0 until length) {
                carry += digits[i] * BYTE_RADIX
                digits[i] = carry % BASE
                carry /= BASE
            }
            while (carry > 0) {
                digits[length++] = carry % BASE
                carry /= BASE
            }
        }
        return buildString(leadingZeros + length) {
            repeat(leadingZeros) { append(ALPHABET[0]) }
            for (i in length - 1 downTo 0) append(ALPHABET[digits[i]])
        }
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val leadingOnes = input.takeWhile { it == ALPHABET[0] }.length
        val bytes = IntArray(input.length)
        var length = 0
        for (char in input) {
            var carry = ALPHABET.indexOf(char)
            require(carry >= 0) { "Invalid Base58 character" }
            for (i in 0 until length) {
                carry += bytes[i] * BASE
                bytes[i] = carry and BYTE_MASK
                carry = carry shr Byte.SIZE_BITS
            }
            while (carry > 0) {
                bytes[length++] = carry and BYTE_MASK
                carry = carry shr Byte.SIZE_BITS
            }
        }
        val out = ByteArray(leadingOnes + length)
        for (i in 0 until length) out[out.size - 1 - i] = bytes[i].toByte()
        return out
    }
}
