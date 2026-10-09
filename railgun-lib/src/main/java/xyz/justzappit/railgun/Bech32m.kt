// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

/** BIP-350's bech32m, the encoding of Railgun's `0zk` addresses. */
internal object Bech32m {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val SEPARATOR = '1'
    private const val CHECKSUM_WORDS = 6
    private const val CONSTANT = 0x2bc830a3
    private const val WORD_BITS = 5
    private const val BYTE_BITS = 8
    private const val HIGH_BITS_SHIFT = 5
    private const val LOW_BITS_MASK = 0x1f
    private const val TOP_SHIFT = 25
    private const val CHECKSUM_MASK = 0x1ffffff
    private const val BYTE_MASK = 0xff
    private const val BUFFER_MASK = (1 shl (BYTE_BITS + WORD_BITS)) - 1
    private const val FIRST_PRINTABLE = 33
    private const val LAST_PRINTABLE = 126

    // BIP-173's generator, one term for each of the checksum's top five bits.
    private const val GENERATOR_0 = 0x3b6a57b2
    private const val GENERATOR_1 = 0x26508e6d
    private const val GENERATOR_2 = 0x1ea119fa
    private const val GENERATOR_3 = 0x3d4233dd
    private const val GENERATOR_4 = 0x2a1462b3
    private val GENERATOR = intArrayOf(GENERATOR_0, GENERATOR_1, GENERATOR_2, GENERATOR_3, GENERATOR_4)

    /** The prefix and payload of lowercase [value], or null unless its checksum holds and its data are whole bytes. */
    fun decode(value: String): Decoded? {
        val separator = value.lastIndexOf(SEPARATOR)
        val prefix = value.take(separator.coerceAtLeast(0))
        val words = value.drop(separator + 1).map(CHARSET::indexOf)
        val isWellFormed =
            separator > 0 &&
                words.size >= CHECKSUM_WORDS &&
                words.none { it < 0 } &&
                prefix.all { it.code in FIRST_PRINTABLE..LAST_PRINTABLE } &&
                polymod(expand(prefix) + words) == CONSTANT
        return if (isWellFormed) toBytes(words.dropLast(CHECKSUM_WORDS))?.let { Decoded(prefix, it) } else null
    }

    class Decoded(
        val prefix: String,
        val data: ByteArray,
    )

    private fun expand(prefix: String): List<Int> =
        prefix.map { it.code shr HIGH_BITS_SHIFT } + 0 + prefix.map { it.code and LOW_BITS_MASK }

    private fun polymod(values: List<Int>): Int =
        values.fold(1) { checksum, value ->
            val top = checksum ushr TOP_SHIFT
            GENERATOR.indices.fold(((checksum and CHECKSUM_MASK) shl WORD_BITS) xor value) { next, bit ->
                if (((top shr bit) and 1) == 1) next xor GENERATOR[bit] else next
            }
        }

    // Five-bit words to bytes; what's left over must be fewer than five bits, all zero.
    private fun toBytes(words: List<Int>): ByteArray? {
        var buffer = 0
        var bits = 0
        val bytes = ArrayList<Byte>(words.size * WORD_BITS / BYTE_BITS)
        words.forEach { word ->
            buffer = ((buffer shl WORD_BITS) or word) and BUFFER_MASK
            bits += WORD_BITS
            if (bits >= BYTE_BITS) {
                bits -= BYTE_BITS
                bytes += ((buffer shr bits) and BYTE_MASK).toByte()
            }
        }
        return bytes.toByteArray().takeIf { bits < WORD_BITS && (buffer and ((1 shl bits) - 1)) == 0 }
    }
}
