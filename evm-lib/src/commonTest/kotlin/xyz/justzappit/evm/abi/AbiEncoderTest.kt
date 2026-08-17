// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AbiEncoderTest {
    @Test
    fun `function selector for approve matches the well-known value`() {
        assertEquals(
            Selector4.fromHex("0x095ea7b3"),
            Selector4.fromCanonicalSignature("approve(address,uint256)"),
        )
    }

    @Test
    fun `function selector for transfer matches the well-known value`() {
        assertEquals(
            Selector4.fromHex("0xa9059cbb"),
            Selector4.fromCanonicalSignature("transfer(address,uint256)"),
        )
    }

    @Test
    fun `function selector for balanceOf matches the well-known value`() {
        assertEquals(
            Selector4.fromHex("0x70a08231"),
            Selector4.fromCanonicalSignature("balanceOf(address)"),
        )
    }

    @Test
    fun `function selector for placeOrder is byte-stable`() {
        // Frozen at the canonical SDK signature. Any change here would mean the canonical
        // signature drifted (function added/removed/renamed/retyped) — re-derive after
        // cross-checking the SDK's order-flow-facet ABI.
        assertEquals(
            Selector4.fromHex("0x1dc46885"),
            Selector4.fromCanonicalSignature(
                "placeOrder(string,uint256,address,uint8,string,string,bytes32,uint256,uint256,uint256)",
            ),
        )
    }

    @Test
    fun `function selector for setSellOrderUpi is byte-stable`() {
        assertEquals(
            Selector4.fromHex("0xe8576b23"),
            Selector4.fromCanonicalSignature("setSellOrderUpi(uint256,string,uint256)"),
        )
    }

    @Test
    fun `uint256 encodes as 32-byte big-endian left-padded with zeros`() {
        val encoded = AbiEncoder.encode(listOf(AbiUint(bigIntegerValueOf(1_000_000)))).toHex()
        assertEquals("00000000000000000000000000000000000000000000000000000000000f4240", encoded)
    }

    @Test
    fun `address encodes left-padded with 12 zero bytes`() {
        val addr = Address.parse("0xce868398FDaDcA368EAc203222874D6888532aE2")
        val encoded = AbiEncoder.encode(listOf(AbiAddress(addr))).toHex()
        assertEquals("000000000000000000000000ce868398fdadca368eac203222874d6888532ae2", encoded)
    }

    @Test
    fun `bytes32 encodes as-is with 32 bytes`() {
        val data = ByteArray(32) { 0xab.toByte() }
        val encoded = AbiEncoder.encode(listOf(AbiBytes32(data))).toHex()
        assertEquals("ab".repeat(32), encoded)
    }

    @Test
    fun `bytes32-of-string right-pads with zeros`() {
        val encoded = AbiEncoder.encode(listOf(AbiEncoder.bytes32String("INR"))).toHex()
        assertEquals("494e520000000000000000000000000000000000000000000000000000000000", encoded)
    }

    @Test
    fun `uint8 encodes as 32-byte left-padded value`() {
        val encoded = AbiEncoder.encode(listOf(AbiUint8(2))).toHex()
        assertEquals("0000000000000000000000000000000000000000000000000000000000000002", encoded)
    }

    @Test
    fun `bool true and false encode correctly`() {
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000001",
            AbiEncoder.encode(listOf(AbiBool(true))).toHex(),
        )
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000000",
            AbiEncoder.encode(listOf(AbiBool(false))).toHex(),
        )
    }

    @Test
    fun `int256 encodes positive as left-padded and negative as twos-complement`() {
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000005",
            AbiEncoder.encode(listOf(AbiInt(bigIntegerValueOf(5)))).toHex(),
        )
        // -1 → 0xff..ff
        assertEquals(
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            AbiEncoder.encode(listOf(AbiInt(bigIntegerValueOf(-1)))).toHex(),
        )
    }

    @Test
    fun `approve calldata matches the hand-computed reference`() {
        // approve(0xce868398FDaDcA368EAc203222874D6888532aE2, 1_000_000)
        val calldata =
            AbiEncoder
                .encodeFunctionCall(
                    "approve(address,uint256)",
                    listOf(
                        AbiAddress(Address.parse("0xce868398FDaDcA368EAc203222874D6888532aE2")),
                        AbiUint(bigIntegerValueOf(1_000_000)),
                    ),
                ).toHex()
        assertEquals(
            "095ea7b3" +
                "000000000000000000000000ce868398fdadca368eac203222874d6888532ae2" +
                "00000000000000000000000000000000000000000000000000000000000f4240",
            calldata,
        )
    }

    @Test
    fun `string encodes with offset length and right-padded data`() {
        // single dynamic arg → head is offset 0x20, tail is length + data
        val encoded = AbiEncoder.encode(listOf(AbiString("hello"))).toHex()
        val expected =
            "0000000000000000000000000000000000000000000000000000000000000020" + // offset
                "0000000000000000000000000000000000000000000000000000000000000005" + // length
                "68656c6c6f000000000000000000000000000000000000000000000000000000" // "hello" + padding
        assertEquals(expected, encoded)
    }

    @Test
    fun `string longer than 32 bytes spans multiple words with zero-padding`() {
        val s = "a".repeat(40)
        val encoded = AbiEncoder.encode(listOf(AbiString(s))).toHex()
        // offset 32, length 40, then 64 bytes of payload (40 + 24 zeros)
        val payload = "61".repeat(40) + "00".repeat(24)
        val expected =
            "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000028" +
                payload
        assertEquals(expected, encoded)
    }

    @Test
    fun `mixed static and dynamic args use correct offsets`() {
        // (uint256, string, uint256) — string starts at offset 96 (3 head slots),
        // length+data follow.
        val encoded =
            AbiEncoder
                .encode(
                    listOf(
                        AbiUint(bigIntegerValueOf(42)),
                        AbiString("ab"),
                        AbiUint(bigIntegerValueOf(7)),
                    ),
                ).toHex()
        val expected =
            "000000000000000000000000000000000000000000000000000000000000002a" + // 42
                "0000000000000000000000000000000000000000000000000000000000000060" + // offset 96
                "0000000000000000000000000000000000000000000000000000000000000007" + // 7
                "0000000000000000000000000000000000000000000000000000000000000002" + // length 2
                "6162000000000000000000000000000000000000000000000000000000000000" // "ab" + padding
        assertEquals(expected, encoded)
    }

    @Test
    fun `empty string still occupies one length word`() {
        val encoded = AbiEncoder.encode(listOf(AbiString(""))).toHex()
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000000",
            encoded,
        )
    }

    @Test
    fun `invalid address length is rejected`() {
        assertFailsWith<IllegalArgumentException> { Address.parse("0xabcd") }
    }

    @Test
    fun `bytes32 wrong size is rejected`() {
        assertFailsWith<IllegalArgumentException> { AbiBytes32(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { AbiBytes32(ByteArray(33)) }
    }

    @Test
    fun `bytes32String rejects oversized inputs`() {
        assertFailsWith<IllegalArgumentException> { AbiEncoder.bytes32String("a".repeat(33)) }
    }

    @Test
    fun `negative uint is rejected`() {
        assertFailsWith<IllegalArgumentException> { AbiUint(bigIntegerValueOf(-1)) }
    }

    @Test
    fun `uint8 out of range is rejected`() {
        assertFailsWith<IllegalArgumentException> { AbiUint8(256) }
        assertFailsWith<IllegalArgumentException> { AbiUint8(-1) }
    }

    @Test
    fun `int256 accepts signed boundaries and rejects out-of-range`() {
        val max = bigIntegerValueOf(2).pow(255) - bigIntegerOne // 2^255 - 1
        val min = bigIntegerValueOf(2).pow(255).negate() // -2^255
        AbiInt(max)
        AbiInt(min)
        assertFailsWith<IllegalArgumentException> { AbiInt(max + bigIntegerOne) } // 2^255
        assertFailsWith<IllegalArgumentException> { AbiInt(min - bigIntegerOne) } // -2^255 - 1
    }
}
