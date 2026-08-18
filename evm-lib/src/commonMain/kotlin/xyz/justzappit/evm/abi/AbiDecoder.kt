// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address

/**
 * Minimal ABI return-data reader — the decode-side counterpart to [AbiEncoder]. Wraps a buffer and
 * exposes word-indexed accessors for the fixed-layout tuples the p2p.me Diamond returns. Not a
 * general ABI codec: it covers the static-word and dynamic-`string` shapes those calls use, and
 * fails loudly on out-of-bounds reads so a malformed response can't masquerade as valid data.
 */
class AbiDecoder(
    private val data: ByteArray
) {
    val byteSize: Int get() = data.size

    /** Asserts the buffer holds at least [words] 32-byte words. */
    fun requireWords(words: Int) {
        require(data.size >= words * WORD) {
            "ABI return data too short: ${data.size} bytes (need ${words * WORD})"
        }
    }

    /** The raw 32-byte word at [wordIndex]. */
    fun word(wordIndex: Int): ByteArray {
        val start = wordIndex * WORD
        return data.copyOfRange(start, start + WORD)
    }

    /** The word at [wordIndex] as an unsigned big-endian integer. */
    fun uint(wordIndex: Int): BigInteger = BigInteger(1, word(wordIndex))

    /** The low byte of the word at [wordIndex] — the ABI encoding of a small `uint8`/enum value. */
    fun uint8(wordIndex: Int): Int = data[(wordIndex + 1) * WORD - 1].toInt() and 0xff

    /** The low 20 bytes of the word at [wordIndex] as an [Address]. */
    fun address(wordIndex: Int): Address = Address.fromBytes(addressBytes(wordIndex))

    /** Like [address], but returns null when the word is all-zero (the contract's "unset" sentinel). */
    fun addressOrNull(wordIndex: Int): Address? {
        val bytes = addressBytes(wordIndex)
        return if (bytes.all { it == 0.toByte() }) null else Address.fromBytes(bytes)
    }

    /**
     * Decodes a dynamic `string` whose tail starts at [byteOffset] (relative to this buffer).
     * Offset 0 is the contract's sentinel for an unset field and a zero-length string is
     * legitimately empty — both yield "". Any offset/length pointing outside the buffer is corrupt
     * return data and throws, rather than yielding a plausible-but-empty value that could silently
     * stall a caller polling on the decoded result.
     */
    fun dynamicStringAt(byteOffset: Int): String {
        if (byteOffset == 0) return ""
        require(byteOffset in 1..(data.size - WORD)) {
            "ABI string offset $byteOffset out of bounds (buffer is ${data.size} bytes)"
        }
        val length = BigInteger(1, data.copyOfRange(byteOffset, byteOffset + WORD)).toInt()
        if (length == 0) return ""
        val dataStart = byteOffset + WORD
        val dataEnd = dataStart.toLong() + length.toLong()
        require(length > 0 && dataEnd <= data.size) {
            "ABI string of $length bytes at offset $byteOffset overruns ${data.size}-byte buffer"
        }
        return data.copyOfRange(dataStart, dataEnd.toInt()).decodeToString()
    }

    private fun addressBytes(wordIndex: Int): ByteArray =
        word(wordIndex).copyOfRange(WORD - Address.LEN_BYTES, WORD)

    companion object {
        const val WORD = 32
    }
}
