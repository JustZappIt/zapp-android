// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderReaderTest {
    @Test
    fun `decodeOrder picks status acceptedMerchant and merchant pubkey at known offsets`() {
        val merchant = "0x1234567890123456789012345678901234567890"
        val pubKey = "abcdef0123456789".repeat(8) // 128 chars, eth-crypto format

        val data =
            synthOrderReturnData(
                status = OrderStatus.ACCEPTED,
                acceptedMerchant = merchant,
                pubkey = pubKey,
            )
        val order = OrderReader.decodeOrder(data)
        assertEquals(OrderStatus.ACCEPTED, order.status)
        assertEquals(Address.parse(merchant), order.acceptedMerchant)
        assertEquals(pubKey, order.merchantPubKey)
    }

    @Test
    fun `decodeOrder handles each status enum value`() {
        for (s in OrderStatus.values()) {
            val data = synthOrderReturnData(status = s)
            assertEquals(s, OrderReader.decodeOrder(data).status)
        }
    }

    @Test
    fun `decodeOrder returns empty pubkey when offset is zero`() {
        val data =
            synthOrderReturnData(
                status = OrderStatus.PLACED,
                acceptedMerchant = "0x" + "00".repeat(20),
                pubkey = null,
            )
        val order = OrderReader.decodeOrder(data)
        assertEquals("", order.merchantPubKey)
    }

    @Test
    fun `decodeOrder returns empty pubkey for a valid zero-length string`() {
        // A real empty string (valid offset, length 0) is legitimately empty, not corrupt.
        val data = synthOrderReturnData(status = OrderStatus.PLACED, pubkey = "")
        assertEquals("", OrderReader.decodeOrder(data).merchantPubKey)
    }

    @Test
    fun `decodeOrder throws when a dynamic-string offset points outside the tuple`() {
        val data = synthOrderReturnData(status = OrderStatus.ACCEPTED, pubkey = "abc".repeat(20))
        // Tuple slot 8 holds the pubkey offset; in the full buffer that is word index 9 (slot 0 is
        // the top-level offset). Point it past the end of the tuple — must fail, not silently "".
        putUintWord(data, wordIndex = 9, value = bigIntegerValueOf(0xFFFF))
        assertFails { OrderReader.decodeOrder(data) }
    }

    @Test
    fun `decodeOrder throws when a dynamic-string length overruns the tuple`() {
        val data = synthOrderReturnData(status = OrderStatus.ACCEPTED, pubkey = "abc".repeat(20))
        // pubkey tail sits 25 words into the tuple; its length word is at full word index 26.
        putUintWord(data, wordIndex = 26, value = bigIntegerValueOf(0xFFFF))
        assertFails { OrderReader.decodeOrder(data) }
    }

    @Test
    fun `decodeOrder rejects too-short input`() {
        assertFailsWith<IllegalArgumentException> { OrderReader.decodeOrder(ByteArray(64)) }
    }

    @Test
    fun `decodeAddressArrayNonEmpty returns true for length gt 0`() {
        val data = encodeAddressArray(listOf("0x" + "11".repeat(20)))
        assertTrue(OrderReader.decodeAddressArrayNonEmpty(data))
    }

    @Test
    fun `decodeAddressArrayNonEmpty returns false for length 0`() {
        val data = encodeAddressArray(emptyList())
        assertFalse(OrderReader.decodeAddressArrayNonEmpty(data))
    }

    @Test
    fun `decodeAddressArrayNonEmpty returns false for empty input`() {
        assertFalse(OrderReader.decodeAddressArrayNonEmpty(ByteArray(0)))
        assertFalse(OrderReader.decodeAddressArrayNonEmpty(ByteArray(16)))
    }

    /** Builds a synthetic Order struct return value with sentinel values for fields we don't read. */
    private fun synthOrderReturnData(
        status: OrderStatus,
        acceptedMerchant: String = "0x" + "ab".repeat(20),
        pubkey: String? = null,
    ): ByteArray {
        val word = 32
        val headSlots = 25
        val tupleHead = ByteArray(headSlots * word)

        // Slot 5: acceptedMerchant
        val mBytes = acceptedMerchant.removePrefix("0x").hexToBytes()
        mBytes.copyInto(tupleHead, destinationOffset = 5 * word + (word - mBytes.size))

        // Slot 11: status (uint8)
        tupleHead[12 * word - 1] = status.onChain.toByte()

        // Slot 8: pubkey offset (or 0 if null pubkey)
        val tupleTail: ByteArray
        if (pubkey != null) {
            val tailOffset = headSlots * word
            val offsetBytes = bigIntegerValueOf(tailOffset.toLong()).toByteArray()
            offsetBytes.copyInto(tupleHead, destinationOffset = 9 * word - offsetBytes.size)
            val pubKeyBytes = pubkey.encodeToByteArray()
            val pad = if (pubKeyBytes.size % word == 0) 0 else word - (pubKeyBytes.size % word)
            val tail = ByteArray(word + pubKeyBytes.size + pad)
            val lenBytes = bigIntegerValueOf(pubKeyBytes.size.toLong()).toByteArray()
            lenBytes.copyInto(tail, destinationOffset = word - lenBytes.size)
            pubKeyBytes.copyInto(tail, destinationOffset = word)
            tupleTail = tail
        } else {
            tupleTail = ByteArray(0)
        }

        // Top-level offset = 0x20 (pointing past itself to the tuple data)
        val topOffset = ByteArray(word).also { it[word - 1] = 0x20.toByte() }
        return topOffset + tupleHead + tupleTail
    }

    /** Overwrites the 32-byte word at [wordIndex] with [value], right-aligned (big-endian uint). */
    private fun putUintWord(data: ByteArray, wordIndex: Int, value: BigInteger) {
        val word = 32
        val start = wordIndex * word
        for (i in start until start + word) data[i] = 0
        val bytes = value.toByteArray()
        val src = if (bytes.size > word) bytes.copyOfRange(bytes.size - word, bytes.size) else bytes
        src.copyInto(data, destinationOffset = start + word - src.size)
    }

    private fun encodeAddressArray(addresses: List<String>): ByteArray {
        val word = 32
        val out = ByteArray(word * (2 + addresses.size))
        // offset 32
        out[word - 1] = 0x20.toByte()
        // length
        val lenBytes = bigIntegerValueOf(addresses.size.toLong()).toByteArray()
        lenBytes.copyInto(out, destinationOffset = 2 * word - lenBytes.size)
        // addresses
        addresses.forEachIndexed { i, addr ->
            val addrBytes = addr.removePrefix("0x").hexToBytes()
            addrBytes.copyInto(out, destinationOffset = (2 + i) * word + (word - addrBytes.size))
        }
        return out
    }
}
