// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EvmKeyDerivationTest {
    @Test
    fun `canonical abandon mnemonic produces well-known account 0 address`() {
        // Industry-wide BIP-44 Ethereum vector. Same address is produced by MetaMask,
        // Ledger, Trezor, ethers, viem, web3j against this mnemonic at m/44'/60'/0'/0/0.
        val key = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
        assertEquals(Address.parse("0x9858EfFD232B4033E47d90003D41EC34EcaEda94"), key.address)
    }

    @Test
    fun `canonical abandon mnemonic produces well-known private key`() {
        val key = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
        assertEquals(
            "1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727",
            key.privateKey.toHex(),
        )
    }

    @Test
    fun `account index 1 differs from 0`() {
        val a = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
        val b = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 1)
        assertNotEquals(a.address, b.address)
        assertNotEquals(a.privateKey.toHex(), b.privateKey.toHex())
    }

    @Test
    fun `passphrase changes derived key`() {
        val a = EvmKeyDerivation.derive(MNEMONIC, passphrase = "")
        val b = EvmKeyDerivation.derive(MNEMONIC, passphrase = "TREZOR")
        assertNotEquals(a.address, b.address)
    }

    @Test
    fun `mnemonic and passphrase use Unicode compatibility decomposition`() {
        val composed = EvmKeyDerivation.derive("caf\u00e9 abandon", passphrase = "p\u00e1ss")
        val decomposed = EvmKeyDerivation.derive("cafe\u0301 abandon", passphrase = "pa\u0301ss")

        assertEquals(composed.address, decomposed.address)
        assertTrue(composed.privateKey.contentEquals(decomposed.privateKey))
    }

    @Test
    fun `negative account index is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EvmKeyDerivation.derive(MNEMONIC, accountIndex = -1)
        }
    }

    @Test
    fun `fromPrivateKey round-trips through derive`() {
        val derived = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
        val rebuilt = EvmKeyDerivation.fromPrivateKey(derived.privateKey)
        assertEquals(derived.address, rebuilt.address)
        assertTrue(derived.publicKey.contentEquals(rebuilt.publicKey))
    }

    @Test
    fun `private key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> {
            EvmKeyDerivation.fromPrivateKey(ByteArray(31))
        }
        assertFailsWith<IllegalArgumentException> {
            EvmKeyDerivation.fromPrivateKey(ByteArray(33))
        }
    }

    @Test
    fun `zero private key is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EvmKeyDerivation.fromPrivateKey(ByteArray(32))
        }
    }

    @Test
    fun `CharArray and String mnemonic overloads produce identical keys`() {
        val fromString = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
        val fromCharArray = EvmKeyDerivation.derive(MNEMONIC.toCharArray(), accountIndex = 0)
        assertEquals(fromString.address, fromCharArray.address)
        assertTrue(fromString.privateKey.contentEquals(fromCharArray.privateKey))
        assertTrue(fromString.publicKey.contentEquals(fromCharArray.publicKey))
    }

    @Test
    fun `derive does not mutate the caller's CharArray`() {
        // Callers may derive multiple accounts before wiping the source.
        val mnemonic = MNEMONIC.toCharArray()
        val before = mnemonic.copyOf()
        EvmKeyDerivation.derive(mnemonic, accountIndex = 0)
        assertTrue(mnemonic.contentEquals(before), "derive must not mutate the caller's CharArray")
    }

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
    }
}
