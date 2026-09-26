// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicSwapChainTest {
    @Test
    fun swapIdMatchesZecSwapsVector() {
        val maker = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5")
        val userShare =
            (
                "0b42629d5b3f787aba7ccde87574c13f6db6b8fccb7c5cfeb3f4e4081c756461" +
                    "311662156525fcaa692aeef5d2362c2a9ab2083cd6d968c618aec72617aa925a"
            ).hexToBytes()
        assertEquals(
            "297f1ca9d44ff7136dbddb0720ecadc040229e22c03ad0f9e4c648212bfc7b66",
            AtomicSwapChain.swapId(maker, userShare).toHex(),
        )
    }

    @Test
    fun decodesGetSwapsSixteenWords() {
        val words =
            listOf(
                address("09eD1F966745Be18C711C346242c0974DAd7c3e5"),
                uint(1_790_000_000),
                uint(2),
                uint(1),
                address("4444444444444444444444444444444444444444"),
                uint(1_790_000_300),
                address("5764D0044bef5AA839E0dDafE2073421101B9Ed8"),
                uint(1_789_999_000),
                uint(1_000_000),
                uint(0),
                filled(0x0a),
                filled(0x0b),
                filled(0x0c),
                filled(0x0d),
                filled(0x0e),
                filled(0x0f),
            )
        val swap = AtomicSwapChain.decodeSwap(words.reduce(ByteArray::plus))!!
        assertEquals(Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5"), swap.maker)
        assertEquals(1_790_000_000, swap.t0)
        assertEquals(SwapStage.READY, swap.stage)
        assertTrue(swap.paidOut)
        assertEquals(Address.parse("0x4444444444444444444444444444444444444444"), swap.user)
        assertEquals(1_790_000_300, swap.t1)
        assertEquals(1_789_999_000, swap.claimLockUntil)
        assertEquals(0, swap.amount.compareTo(bigIntegerValueOf(1_000_000)))
        assertContentEquals(filled(0x0a) + filled(0x0b), swap.makerShare)
        assertContentEquals(filled(0x0c) + filled(0x0d), swap.userShare)
        assertContentEquals(filled(0x0e), swap.secret)
        assertContentEquals(filled(0x0f), swap.payoutNote)
    }

    @Test
    fun aSwapThatIsNotOpenDecodesToNull() {
        assertNull(AtomicSwapChain.decodeSwap(ByteArray(16 * 32)))
    }

    private fun uint(value: Long) = bigIntegerValueOf(value).toByteArray().let { ByteArray(32 - it.size) + it }

    private fun address(hex: String) = ByteArray(12) + hex.hexToBytes()

    private fun filled(byte: Int) = ByteArray(32) { byte.toByte() }
}
