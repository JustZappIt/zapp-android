// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import org.junit.Test
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.assertEquals

class RailgunMnemonicTest {
    @Test
    fun `BIP-85's 24 English words vector encodes as the spec's mnemonic`() {
        val entropy = "ae131e2312cdc61331542efe0d1077bac5ea803adf24b313a4f0e48e9c51f37f".hexToBytes()

        assertEquals(
            "puppy ocean match cereal symbol another shed magic wrap hammer bulb intact gadget divorce twin tonight " +
                "reason outdoor destroy simple truth cigar social volcano",
            Mnemonics.MnemonicCode(entropy).use { it.chars.concatToString() },
        )
    }

    /** Known answers computed apart from this code, for the Zcash seed of `abandon` × 23 `art`. */
    @Test
    fun `the Railgun mnemonic is BIP-85's child of the Zcash seed`() {
        val zcashSeed = Mnemonics.MnemonicCode((List(23) { "abandon" } + "art").joinToString(" ")).use { it.toSeed() }

        assertEquals(
            "water combine battle truck reopen scene dilemma art raise title fault fade employ inject defense bleak " +
                "flavor document trash timber loud rate receive math",
            railgunMnemonic(zcashSeed).use { it.chars.concatToString() },
        )
        assertEquals(
            "a06f3b95b9ae6a46eded63da4807a1dbb4450d547f72ce309ae49f95fe0a07f9" +
                "ad445c52a12d9057b66e86035ce002b2b8ff2deedefbd555f07e9ce94c76d952",
            railgunSeed(zcashSeed).toHex(),
        )
    }
}
