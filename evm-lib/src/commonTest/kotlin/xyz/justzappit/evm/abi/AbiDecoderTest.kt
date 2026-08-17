// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class AbiDecoderTest {
    @Test
    fun `uint reads a word as an unsigned big-endian integer`() {
        val d = AbiDecoder(uintWord(0) + uintWord(258))
        assertEquals(bigIntegerZero, d.uint(0))
        assertEquals(bigIntegerValueOf(258), d.uint(1))
    }

    @Test
    fun `uint8 reads the low byte of a word`() {
        val d = AbiDecoder(uintWord(3) + uintWord(255))
        assertEquals(3, d.uint8(0))
        assertEquals(255, d.uint8(1))
    }

    @Test
    fun `address reads the low 20 bytes`() {
        val addr = "0x1234567890123456789012345678901234567890"
        val word = ByteArray(12) + addr.hexToBytes()
        assertEquals(Address.parse(addr), AbiDecoder(word).address(0))
    }

    @Test
    fun `addressOrNull returns null for the zero word`() {
        assertNull(AbiDecoder(uintWord(0)).addressOrNull(0))
    }

    @Test
    fun `word returns the raw 32 bytes`() {
        val d = AbiDecoder(uintWord(1) + uintWord(2))
        assertEquals(bigIntegerValueOf(2), BigInteger(1, d.word(1)))
    }

    @Test
    fun `dynamicStringAt decodes a UTF-8 string at an offset`() {
        val text = "merchant@upi"
        val head = uintWord(WORD.toLong()) // single offset word pointing just past itself
        val tail = lengthAndData(text)
        assertEquals(text, AbiDecoder(head + tail).dynamicStringAt(WORD))
    }

    @Test
    fun `dynamicStringAt treats offset 0 and zero length as empty`() {
        assertEquals("", AbiDecoder(uintWord(0)).dynamicStringAt(0))
        val data = uintWord(WORD.toLong()) + uintWord(0) // offset → a zero-length word
        assertEquals("", AbiDecoder(data).dynamicStringAt(WORD))
    }

    @Test
    fun `dynamicStringAt throws when offset points outside the buffer`() {
        assertFails { AbiDecoder(uintWord(0)).dynamicStringAt(9999) }
    }

    @Test
    fun `dynamicStringAt throws when declared length overruns the buffer`() {
        val data = uintWord(WORD.toLong()) + uintWord(9999) // length word claims 9999 bytes of data
        assertFails { AbiDecoder(data).dynamicStringAt(WORD) }
    }

    @Test
    fun `requireWords throws on a short buffer`() {
        assertFails { AbiDecoder(uintWord(0)).requireWords(2) }
    }

    private companion object {
        const val WORD = 32

        /** A 32-byte word holding [value] as a right-aligned unsigned integer. */
        fun uintWord(value: Long): ByteArray {
            val bytes = bigIntegerValueOf(value).toByteArray()
            val src = if (bytes.size > WORD) bytes.copyOfRange(bytes.size - WORD, bytes.size) else bytes
            return ByteArray(WORD).also { src.copyInto(it, WORD - src.size) }
        }

        /** ABI tail for a dynamic string: a length word followed by right-padded UTF-8 data. */
        fun lengthAndData(text: String): ByteArray {
            val bytes = text.encodeToByteArray()
            val pad = if (bytes.size % WORD == 0) 0 else WORD - (bytes.size % WORD)
            return uintWord(bytes.size.toLong()) + bytes + ByteArray(pad)
        }
    }
}
