// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexTest {
    @Test
    fun `round-trip preserves bytes`() {
        val bytes = byteArrayOf(0x00, 0x12, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertContentEquals(bytes, bytes.toHex().hexToBytes())
    }

    @Test
    fun `accepts 0x prefix`() {
        assertContentEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte()), "0xabcd".hexToBytes())
        assertContentEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte()), "0Xabcd".hexToBytes())
    }

    @Test
    fun `empty string yields empty bytes`() {
        assertContentEquals(byteArrayOf(), "".hexToBytes())
        assertContentEquals(byteArrayOf(), "0x".hexToBytes())
    }

    @Test
    fun `case-insensitive`() {
        assertContentEquals(byteArrayOf(0xab.toByte()), "AB".hexToBytes())
        assertContentEquals(byteArrayOf(0xab.toByte()), "aB".hexToBytes())
    }

    @Test
    fun `odd-length input is rejected`() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
    }

    @Test
    fun `non-hex characters are rejected`() {
        assertFailsWith<IllegalArgumentException> { "az".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "0xzz".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "hello!!".hexToBytes() }
    }

    @Test
    fun `toHex emits lowercase with no prefix`() {
        assertEquals("ff", byteArrayOf(0xff.toByte()).toHex())
        assertEquals("0001ab", byteArrayOf(0x00, 0x01, 0xab.toByte()).toHex())
    }
}
