// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BigIntegerParityTest {
    @Test
    fun `toByteArray matches Java signed encoding boundaries`() {
        mapOf(
            "0" to byteArrayOf(0x00),
            "1" to byteArrayOf(0x01),
            "127" to byteArrayOf(0x7f),
            "128" to byteArrayOf(0x00, 0x80.toByte()),
            "255" to byteArrayOf(0x00, 0xff.toByte()),
            "256" to byteArrayOf(0x01, 0x00),
            "-1" to byteArrayOf(0xff.toByte()),
            "-127" to byteArrayOf(0x81.toByte()),
            "-128" to byteArrayOf(0x80.toByte()),
            "-129" to byteArrayOf(0xff.toByte(), 0x7f),
            "-255" to byteArrayOf(0xff.toByte(), 0x01),
            "-256" to byteArrayOf(0xff.toByte(), 0x00),
        ).forEach { (decimal, expected) ->
            assertContentEquals(expected, BigInteger(decimal).toByteArray(), decimal)
        }
    }

    @Test
    fun `platform constants and value factory match constructors`() {
        assertEquals(BigInteger("0"), bigIntegerZero)
        assertEquals(BigInteger("1"), bigIntegerOne)
        assertEquals(BigInteger("9223372036854775807"), bigIntegerValueOf(Long.MAX_VALUE))
    }
}
