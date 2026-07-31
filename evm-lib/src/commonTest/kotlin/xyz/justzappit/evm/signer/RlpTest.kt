// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.util.toHex
import xyz.justzappit.evm.math.bigIntegerValueOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RlpTest {
    // Vectors from the Ethereum Yellow Paper, Appendix B.

    @Test
    fun `empty string encodes as 0x80`() {
        assertEquals("80", Rlp.encode(rlpEmpty()).toHex())
    }

    @Test
    fun `single byte below 0x80 encodes as itself`() {
        assertEquals("00", Rlp.encode(rlpBytes(byteArrayOf(0x00))).toHex())
        assertEquals("0f", Rlp.encode(rlpBytes(byteArrayOf(0x0f))).toHex())
        assertEquals("7f", Rlp.encode(rlpBytes(byteArrayOf(0x7f))).toHex())
    }

    @Test
    fun `single byte 0x80 encodes with length prefix`() {
        assertEquals("8180", Rlp.encode(rlpBytes(byteArrayOf(0x80.toByte()))).toHex())
    }

    @Test
    fun `dog string encodes per yellow paper`() {
        assertEquals("83646f67", Rlp.encode(rlpBytes("dog".encodeToByteArray())).toHex())
    }

    @Test
    fun `cat-dog list encodes per yellow paper`() {
        val encoded =
            Rlp.encode(
                rlpList(
                    rlpBytes("cat".encodeToByteArray()),
                    rlpBytes("dog".encodeToByteArray()),
                ),
            )
        assertEquals("c88363617483646f67", encoded.toHex())
    }

    @Test
    fun `empty list encodes as 0xc0`() {
        assertEquals("c0", Rlp.encode(rlpList(emptyList())).toHex())
    }

    @Test
    fun `nested lists encode per yellow paper`() {
        val inner = rlpList(rlpList(emptyList()))
        val tree =
            rlpList(
                rlpList(emptyList()),
                inner,
                rlpList(rlpList(emptyList()), inner),
            )
        assertEquals("c7c0c1c0c3c0c1c0", Rlp.encode(tree).toHex())
    }

    @Test
    fun `integer zero is rlpEmpty`() {
        assertEquals("80", Rlp.encode(rlpInt(0)).toHex())
    }

    @Test
    fun `integer 1024 encodes as 0x820400`() {
        assertEquals("820400", Rlp.encode(rlpInt(bigIntegerValueOf(1_024))).toHex())
    }

    @Test
    fun `negative integers are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            rlpInt(bigIntegerValueOf(-1))
        }
    }

    @Test
    fun `long string over 55 bytes uses long-form length prefix`() {
        val s = "Lorem ipsum dolor sit amet, consectetur adipisicing elit"
        val encoded = Rlp.encode(rlpBytes(s.encodeToByteArray())).toHex()
        assertEquals("b8" + "38" + s.encodeToByteArray().toHex(), encoded)
    }
}
