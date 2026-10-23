// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.deposits

import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.signer.EcdsaSigner
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.types.Address
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DepositKeysTest {
    @Test
    fun `the key whose scalar is 1 has Ethereum's well-known address`() {
        assertEquals(SCALAR_ONE_ADDRESS.checksumHex, scalarOne().address.checksumHex)
    }

    @Test
    fun `signatures are byte for byte the ones Foundry's vm sign makes over the known answers`() {
        // `cast wallet sign --no-hash` with the scalar-1 key: what contracts/test/Deposits.t.sol lands a shield and a
        // rescue with. Both are RFC 6979's deterministic signatures.
        val key = scalarOne()
        val authorization = VECTOR_TOKEN.receiveWithAuthorization(vectorAuthorization())
        val rescue = VECTOR_DEPOSITS.rescue(SCALAR_ONE_ADDRESS, VECTOR_NOTE, address(0x77), FEE, 0, VALID_BEFORE)
        assertEquals(
            "0x683ba5be486df1b68fd9672dd500828c61b6b91133b3562fbe325b6b01b9b99f" +
                "1dee234ddd1a8c30769edf9830f7f076d29c3927fb5720125f92d14e270062361b",
            key.sign(authorization).hex
        )
        assertEquals(
            "0x9af2613416d4daa97582196e906bcb206ddf07c44df7e1dc9d9cbf43a1aa5beb" +
                "1fa1bdb63cb909e332307b6b06c03b29c9195f62a52dc752f1beb498998c97e31b",
            key.sign(rescue).hex
        )
        assertEquals(SCALAR_ONE_ADDRESS, signer(authorization, key.sign(authorization)))
        assertEquals(SCALAR_ONE_ADDRESS, signer(rescue, key.sign(rescue)))
    }

    @Test
    fun `a deposit key's signatures recover to its address, with a low s and v 27 or 28`() {
        DepositKeys.withKey(SEED, mainnet = true, account = 0, index = 0) { key ->
            repeat(64) { i ->
                val digest = keccak256(byteArrayOf(i.toByte()))
                val signature = key.sign(digest).bytes
                assertEquals(DepositSignature.SIZE, signature.size)
                assertTrue(BigInteger(1, signature.copyOfRange(32, 64)) <= SECP256K1_N.shiftRight(1), "a high s")
                assertTrue(signature[64] == 27.toByte() || signature[64] == 28.toByte(), "v ${signature[64]}")
                assertEquals(key.address, signer(digest, key.sign(digest)))
                assertNotEquals(key.address, signer(keccak256(digest), key.sign(digest)))
            }
        }
    }

    @Test
    fun `withKey wipes the key once its block returns, or throws`() {
        val escaped = DepositKeys.withKey(SEED, mainnet = true, account = 0, index = 0) { it }
        assertFailsWith<IllegalArgumentException> { escaped.sign(repeated(0x01)) }

        var thrownOut: DepositKey? = null
        assertFailsWith<IllegalStateException> {
            DepositKeys.withKey(SEED, mainnet = true, account = 0, index = 0) {
                thrownOut = it
                error("the block failed")
            }
        }
        assertFailsWith<IllegalArgumentException> { checkNotNull(thrownOut).sign(repeated(0x01)) }
    }

    @Test
    fun `the seed is read, never changed`() {
        val seed = SEED.copyOf()
        DepositKeys.withKey(seed, mainnet = true, account = 0, index = 0) { it.sign(repeated(0x01)) }
        DepositKeys.addresses(seed, mainnet = false, account = 0, indices = 0 until 3)
        assertContentEquals(SEED, seed)
    }

    @Test
    fun `index, account, network and seed each give another address`() {
        val base = DepositKeys.address(SEED, mainnet = true, account = 0, index = 0)
        val others =
            listOf(
                DepositKeys.address(SEED, mainnet = true, account = 0, index = 1),
                DepositKeys.address(SEED, mainnet = true, account = 1, index = 0),
                DepositKeys.address(SEED, mainnet = false, account = 0, index = 0),
                DepositKeys.address(ByteArray(64) { 43 }, mainnet = true, account = 0, index = 0),
            )
        others.forEach { assertNotEquals(base, it) }
        assertEquals(base, DepositKeys.address(SEED.copyOf(), mainnet = true, account = 0, index = 0))
    }

    @Test
    fun `a window of addresses is each address derived on its own`() {
        val window = DepositKeys.addresses(SEED, mainnet = true, account = 0, indices = 20 until 40)
        assertEquals(20, window.size)
        window.forEachIndexed { i, address ->
            assertEquals(DepositKeys.address(SEED, mainnet = true, account = 0, index = 20 + i), address)
        }
        assertEquals(20, window.toSet().size)
        assertEquals(emptyList(), DepositKeys.addresses(SEED, mainnet = true, account = 0, indices = 5 until 5))
    }

    @Test
    fun `paths are written as wallets take them`() {
        assertEquals("m/44'/60'/1000'/0/0", DepositKeys.path(mainnet = true, account = 0, index = 0))
        assertEquals("m/44'/1'/1007'/0/3", DepositKeys.path(mainnet = false, account = 7, index = 3))
        assertEquals(
            "m/44'/60'/2147483647'/0/2147483647",
            DepositKeys.path(mainnet = true, account = Int.MAX_VALUE - 1000, index = Int.MAX_VALUE)
        )
    }

    @Test
    fun `the last account and index BIP-32 reaches derive`() {
        val last = DepositKeys.address(SEED, mainnet = true, account = Int.MAX_VALUE - 1000, index = Int.MAX_VALUE)
        assertNotEquals(DepositKeys.address(SEED, mainnet = true, account = 0, index = 0), last)
    }

    @Test
    fun `seeds BIP-32 doesn't take, and accounts and indices it can't reach, are refused`() {
        listOf(0, 15, 20, 33, 63, 65).forEach { size ->
            assertFailsWith<IllegalArgumentException>("$size bytes") {
                DepositKeys.address(ByteArray(size) { 1 }, mainnet = true, account = 0, index = 0)
            }
        }
        listOf(16, 32, 64).forEach { size ->
            DepositKeys.address(ByteArray(size) { 1 }, mainnet = true, account = 0, index = 0)
        }
        listOf(-1, Int.MAX_VALUE - 999, Int.MAX_VALUE).forEach { account ->
            assertFailsWith<IllegalArgumentException>("account $account") {
                DepositKeys.address(SEED, mainnet = true, account = account, index = 0)
            }
            assertFailsWith<IllegalArgumentException> { DepositKeys.path(mainnet = true, account = account, index = 0) }
            assertFailsWith<IllegalArgumentException> {
                DepositKeys.addresses(SEED, mainnet = true, account = account, indices = 0 until 2)
            }
        }
        assertFailsWith<IllegalArgumentException> { DepositKeys.address(SEED, mainnet = true, account = 0, index = -1) }
        assertFailsWith<IllegalArgumentException> {
            DepositKeys.addresses(SEED, mainnet = true, account = 0, indices = -1 until 2)
        }
    }

    @Test
    fun `only a 32-byte digest is signed`() {
        val key = scalarOne()
        assertFailsWith<IllegalArgumentException> { key.sign(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { key.sign(ByteArray(33)) }
    }

    @Test
    fun `a signature is 65 bytes, written as 0x and 130 hex digits, and never printed`() {
        val signature = scalarOne().sign(repeated(0x01))
        assertEquals(2 + 130, signature.hex.length)
        assertTrue(signature.hex.startsWith("0x"))
        assertEquals("DepositSignature", signature.toString())
        signature.bytes.fill(0)
        assertEquals(scalarOne().sign(repeated(0x01)), signature)
        assertEquals(scalarOne().sign(repeated(0x01)).hashCode(), signature.hashCode())
    }

    private fun scalarOne(): DepositKey =
        DepositKey(EvmKeyDerivation.fromPrivateKey(ByteArray(32) { if (it == 31) 1 else 0 }))

    private companion object {
        /** A 64-byte seed, as a BIP-39 mnemonic gives. */
        val SEED = ByteArray(64) { 42 }
    }
}

/** Who signed [digest], as `ecrecover` finds it, if [signature] has a low `s` and `v` 27 or 28 (zecSwap's `signer`). */
internal fun signer(
    digest: ByteArray,
    signature: DepositSignature,
): Address? {
    val bytes = signature.bytes
    val s = BigInteger(1, bytes.copyOfRange(32, 64))
    val v = bytes[64].toInt()
    if (v != 27 && v != 28 || s > SECP256K1_N.shiftRight(1)) return null
    val point =
        EcdsaSigner.recoverPublicKeyBytes(v - 27, BigInteger(1, bytes.copyOfRange(0, 32)), s, digest) ?: return null
    val hash = keccak256(point.copyOfRange(1, point.size))
    return Address.fromBytes(hash.copyOfRange(hash.size - 20, hash.size))
}
