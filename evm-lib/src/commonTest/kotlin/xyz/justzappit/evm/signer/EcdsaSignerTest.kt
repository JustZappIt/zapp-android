// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcdsaSignerTest {
    @Test
    fun `signature is deterministic per RFC 6979`() {
        val priv = BigInteger("1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727", 16)
        val hash = ByteArray(32) { 0x11 }
        val a = EcdsaSigner.sign(hash, priv)
        val b = EcdsaSigner.sign(hash, priv)
        assertEquals(a.r, b.r)
        assertEquals(a.s, b.s)
        assertEquals(a.yParity, b.yParity)
    }

    @Test
    fun `signature is low-S below n div 2`() {
        val priv = BigInteger("1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727", 16)
        val halfN = SECP256K1_N.shiftRight(1)
        val sig = EcdsaSigner.sign(ByteArray(32) { 0x22 }, priv)
        assertTrue(sig.s <= halfN, "s=${sig.s.toString(16)} > n/2=${halfN.toString(16)}")
    }

    @Test
    fun `sign then recover yields the signer's public point`() {
        val privateBytes = PRIVATE_KEY_HEX.hexToBytes()
        val priv = BigInteger(1, privateBytes)
        val hash = ByteArray(32) { 0x33 }

        val sig = EcdsaSigner.sign(hash, priv)
        val recovered = EcdsaSigner.recoverPublicKeyBytes(sig.yParity.toInt(), sig.r, sig.s, hash)
        assertNotNull(recovered)
        assertTrue(secpPublicKeyUncompressed(privateBytes).contentEquals(recovered))
    }

    @Test
    fun `recovered point matches the EOA address`() {
        val privateBytes = PRIVATE_KEY_HEX.hexToBytes()
        val priv = BigInteger(1, privateBytes)
        val hash = "abcd".padEnd(64, '0').hexToBytes()

        val sig = EcdsaSigner.sign(hash, priv)
        val recovered = EcdsaSigner.recoverPublicKeyBytes(sig.yParity.toInt(), sig.r, sig.s, hash)!!
        assertTrue(recovered.copyOfRange(1, recovered.size).contentEquals(secpPublicKeyUncompressed(privateBytes).copyOfRange(1, 65)))
    }

    @Test
    fun `wrong recovery id yields a different point`() {
        val priv = BigInteger("ab".repeat(32), 16).mod(SECP256K1_N)
        val hash = ByteArray(32) { 0x44 }
        val sig = EcdsaSigner.sign(hash, priv)
        val correct = EcdsaSigner.recoverPublicKeyBytes(sig.yParity.toInt(), sig.r, sig.s, hash)!!
        val wrong = EcdsaSigner.recoverPublicKeyBytes(1 - sig.yParity.toInt(), sig.r, sig.s, hash)
        if (wrong != null) {
            assertTrue(
                !correct.contentEquals(wrong),
                "Recovery with wrong recId returned the same point",
            )
        }
    }

    @Test
    fun `recovery returns null for out-of-range r or s`() {
        val hash = ByteArray(32)
        assertNull(EcdsaSigner.recoverPublicKeyBytes(0, bigIntegerZero, bigIntegerOne, hash))
        assertNull(EcdsaSigner.recoverPublicKeyBytes(0, bigIntegerOne, bigIntegerZero, hash))
        assertNull(EcdsaSigner.recoverPublicKeyBytes(0, SECP256K1_N, bigIntegerOne, hash))
    }

    companion object {
        const val PRIVATE_KEY_HEX = "1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727"
    }
}
