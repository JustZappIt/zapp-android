// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The test vectors of BIP-85, from its master key. */
class Bip85Test {
    @Test
    fun `the derived key and entropy of the first test case`() {
        val node = Bip32.derive(root(), listOf(83_696_968, 0, 0).map { it or Bip32.HARDENED })

        assertEquals("cca20ccb0e9a90feb0912870c3323b24874b0ca3d8018c4b96d0b97c0e82ded0", node.privateKey.toHex())
        assertEquals(
            "efecfbccffea313214232d29e71563d941229afb4338c21f9517c41aaa0d16f00b83d2a09ef747e7a64e8e2bd5a14869e693da" +
                "66ce94ac2da570ab7ee48618f7",
            Bip85.entropy(root(), listOf(0, 0)).toHex(),
        )
    }

    @Test
    fun `BIP-39 entropy for 12, 18 and 24 English words`() {
        assertEquals("6250b68daf746d12a24d58b4787a714b", Bip85.bip39Entropy(root(), 12, 0).toHex())
        assertEquals("938033ed8b12698449d4bbca3c853c66b293ea1b1ce9d9dc", Bip85.bip39Entropy(root(), 18, 0).toHex())
        assertEquals(
            "ae131e2312cdc61331542efe0d1077bac5ea803adf24b313a4f0e48e9c51f37f",
            Bip85.bip39Entropy(root(), 24, 0).toHex(),
        )
    }

    @Test
    fun `each index and seed has entropy of its own`() {
        val seed = ByteArray(64) { 7 }
        val first = Bip85.bip39Entropy(seed, 24, 0).toHex()

        assertEquals(first, Bip85.bip39Entropy(seed, 24, 0).toHex())
        assertNotEquals(first, Bip85.bip39Entropy(seed, 24, 1).toHex())
        assertNotEquals(first, Bip85.bip39Entropy(ByteArray(64) { 8 }, 24, 0).toHex())
    }

    @Test
    fun `word counts BIP-39 lacks and negative indices are refused`() {
        val seed = ByteArray(64) { 7 }
        listOf(0, 11, 13, 25).forEach { words ->
            assertFailsWith<IllegalArgumentException> { Bip85.bip39Entropy(seed, words, 0) }
        }
        assertFailsWith<IllegalArgumentException> { Bip85.bip39Entropy(seed, 24, -1) }
    }

    private companion object {
        const val MASTER =
            "xprv9s21ZrQH143K2LBWUUQRFXhucrQqBpKdRRxNVq2zBqsx8HVqFk2uYo8kmbaLLHRdqtQpUm98uKfu3vca1Lq" +
                "dGhUtyoFnCNkfmXRyPXLjbKb"
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** [MASTER] decoded: its chain code is bytes 13 to 45, its key the 32 bytes after a zero. */
        fun root(): ExtendedKey {
            val xprv =
                MASTER
                    .fold(bigIntegerZero) { value, digit ->
                        value * bigIntegerValueOf(58) + bigIntegerValueOf(BASE58.indexOf(digit).toLong())
                    }.toByteArray()
                    .takeLast(82)
                    .toByteArray()
            return ExtendedKey(privateKey = xprv.copyOfRange(46, 78), chainCode = xprv.copyOfRange(13, 45))
        }
    }
}
