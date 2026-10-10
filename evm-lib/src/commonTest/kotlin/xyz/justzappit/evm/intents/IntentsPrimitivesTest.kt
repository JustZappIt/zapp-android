// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.signer.toUnsignedFieldBytes
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class IntentsPrimitivesTest {
    @Test
    fun `base58 matches the Bitcoin reference vectors`() {
        // From the Base58 test vectors used by Bitcoin Core (base58_encode_decode.json).
        listOf(
            "" to "",
            "61" to "2g",
            "626262" to "a3gV",
            "636363" to "aPEr",
            "73696d706c792061206c6f6e6720737472696e67" to "2cFupjhnEsSn59qHXstmK2ffpLv2",
            "00eb15231dfceb60925886b67d065299925915aeb172c06647" to "1NS17iag9jJgTHD1VXjvLCEnZuQ3rJDE9L",
            "516b6fcd0f" to "ABnLTmg",
            "bf4f89001e670274dd" to "3SEo3LWLoPntC",
            "572e4794" to "3EFU7m",
            "ecac89cad93923c02321" to "EJDM8drfXA6uyA",
            "10c8511e" to "Rt5zm",
            "00000000000000000000" to "1111111111",
        ).forEach { (hex, encoded) ->
            assertEquals(encoded, Base58.encode(hex.hexToBytes()), "encode $hex")
            assertEquals(hex, Base58.decode(encoded).toHex(), "decode $encoded")
        }
    }

    @Test
    fun `base58 rejects characters outside the alphabet`() {
        listOf("0", "O", "I", "l", "+").forEach { bad ->
            assertFailsWith<IllegalArgumentException> { Base58.decode("abc$bad") }
        }
    }

    @Test
    fun `nonce layout puts the start time ahead of the random tail`() {
        val nonce =
            IntentNonce.encode(
                salt = ByteArray(4) { 0x11 },
                deadlineEpochMillis = 2_000,
                startEpochMillis = 1_000,
                random = ByteArray(7) { 0x22 },
            )
        assertEquals(IntentNonce.SIZE, nonce.size)
        assertEquals("5628f6c600", nonce.copyOfRange(0, 5).toHex())
        assertEquals("11111111", nonce.copyOfRange(5, 9).toHex())
        assertEquals("0094357700000000", nonce.copyOfRange(9, 17).toHex()) // 2_000 ms = 2e9 ns, little-endian
        assertEquals("00ca9a3b00000000", nonce.copyOfRange(17, 25).toHex()) // 1_000 ms = 1e9 ns, little-endian
        assertContentEquals(ByteArray(7) { 0x22 }, nonce.copyOfRange(25, 32))
    }

    @Test
    fun `nonce rejects wrong sizes and non-positive times`() {
        assertFailsWith<IllegalArgumentException> { IntentNonce.encode(ByteArray(3), 1, 1, ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { IntentNonce.encode(ByteArray(4), 1, 1, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { IntentNonce.encode(ByteArray(4), 0, 1, ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { IntentNonce.encode(ByteArray(4), 1, -1, ByteArray(7)) }
    }

    @Test
    fun `private account is not the offramp or MetaMask account`() {
        val words = MNEMONIC.toCharArray()
        val invest = IntentsAccount.derive(words)
        assertNotEquals(EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0).address, invest.address)
        assertNotEquals(EvmKeyDerivation.derive(MNEMONIC, accountIndex = 1).address, invest.address)
        assertEquals(EvmKeyDerivation.deriveAccount(MNEMONIC.toCharArray(), account = 7).address, invest.address)
    }

    @Test
    fun `default account keeps the existing derivation`() {
        assertEquals(
            EvmKeyDerivation.derive(MNEMONIC, accountIndex = 3).address,
            EvmKeyDerivation.deriveAccount(MNEMONIC.toCharArray(), account = 0, accountIndex = 3).address,
        )
        assertFailsWith<IllegalArgumentException> {
            EvmKeyDerivation.deriveAccount(MNEMONIC.toCharArray(), account = -1)
        }
    }

    @Test
    fun `recoverSigner rejects malformed signatures`() {
        val payload = "x"
        assertNull(Erc191IntentSigner.recoverSigner(payload, "ed25519:abc"))
        assertNull(Erc191IntentSigner.recoverSigner(payload, "secp256k1:0OIl"))
        assertNull(Erc191IntentSigner.recoverSigner(payload, "secp256k1:" + Base58.encode(ByteArray(64))))
        val key = IntentsAccount.derive(MNEMONIC.toCharArray())
        val signature = Base58.decode(Erc191IntentSigner.sign(key, payload).removePrefix("secp256k1:"))
        signature[64] = 27 // v as 27 instead of the raw recovery bit
        assertNull(Erc191IntentSigner.recoverSigner(payload, "secp256k1:" + Base58.encode(signature)))
    }

    @Test
    fun `a signature does not verify for a different payload`() {
        val key = IntentsAccount.derive(MNEMONIC.toCharArray())
        val signature = Erc191IntentSigner.sign(key, "move 1")
        assertNotEquals(key.address, Erc191IntentSigner.recoverSigner("move 2", signature))
    }

    @Test
    fun `salt parses only the 8-hex-character view result`() {
        assertEquals("252812b3", IntentNonce.parseSalt("252812b3").toHex())
        listOf("", "252812b", "252812b3aa", "252812B3", "\"252812b3\"", "0x252812").forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { IntentNonce.parseSalt(bad) }
        }
    }

    @Test
    fun `nonce refuses a deadline before its start or beyond year 9999`() {
        val salt = ByteArray(4)
        val random = ByteArray(7)
        assertFailsWith<IllegalArgumentException> { IntentNonce.encode(salt, 1_000, 1_000, random) }
        assertFailsWith<IllegalArgumentException> { IntentNonce.encode(salt, 999, 1_000, random) }
        assertFailsWith<IllegalArgumentException> {
            IntentNonce.encode(salt, IntentNonce.MAX_EPOCH_MILLIS + 1, 1_000, random)
        }
        assertEquals(IntentNonce.SIZE, IntentNonce.encode(salt, IntentNonce.MAX_EPOCH_MILLIS, 1_000, random).size)
    }

    @Test
    fun `login refuses a zero or overlong lifetime`() {
        val key = IntentsAccount.derive(MNEMONIC.toCharArray())
        val salt = IntentNonce.parseSalt("252812b3")
        assertFailsWith<IllegalArgumentException> { IntentsLogin.sign(key, salt, 1_000_000, ttlMillis = 0) }
        assertFailsWith<IllegalArgumentException> { IntentsLogin.sign(key, salt, 1_000_000, ttlMillis = 600_001) }
    }

    @Test
    fun `two logins from the same clock reading use different nonces`() {
        val key = IntentsAccount.derive(MNEMONIC.toCharArray())
        val salt = IntentNonce.parseSalt("252812b3")
        val a = IntentsLogin.sign(key, salt, serverNowMillis = 1_790_604_000_000)
        val b = IntentsLogin.sign(key, salt, serverNowMillis = 1_790_604_000_000)
        assertNotEquals(a.payload, b.payload)
    }

    @Test
    fun `recoverSigner refuses the high-s twin of a valid signature`() {
        val key = IntentsAccount.derive(MNEMONIC.toCharArray())
        val payload = "high-s"
        val raw = Base58.decode(Erc191IntentSigner.sign(key, payload).removePrefix("secp256k1:"))
        val s = BigInteger(1, raw.copyOfRange(32, 64))
        val twin =
            raw.copyOfRange(0, 32) +
                (SECP256K1_N - s).toUnsignedFieldBytes() +
                byteArrayOf((1 - raw[64]).toByte())
        assertEquals(key.address, Erc191IntentSigner.recoverSigner(payload, "secp256k1:" + Base58.encode(raw)))
        assertNull(Erc191IntentSigner.recoverSigner(payload, "secp256k1:" + Base58.encode(twin)))
    }

    @Test
    fun `recoverSigner refuses oversized input before decoding it`() {
        assertNull(Erc191IntentSigner.recoverSigner("x", "secp256k1:" + "2".repeat(100_000)))
    }

    @Test
    fun `login payload only accepts a lowercase 0x account`() {
        listOf(
            "0x8BAE832C68D7C90C11BCA5EB8BD0F14D49BD9E8F",
            "8bae832c68d7c90c11bca5eb8bd0f14d49bd9e8f",
            "alice.near",
            "0x8bae832c68d7c90c11bca5eb8bd0f14d49bd9e8f\",\"x",
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { IntentPayload.build(bad, 1_000, "AA==") }
        }
    }

    @Test
    fun `iso parsing inverts iso formatting and rejects impossible dates`() {
        listOf(0L, 951_868_800_001L, 1_709_251_199_999L, 1_790_604_180_000L, 253_402_300_799_999L).forEach {
            assertEquals(it, IntentPayload.parseIsoMillis(IntentPayload.isoMillis(it)))
        }
        assertEquals(1_790_604_180_000L, IntentPayload.parseIsoMillis("2026-09-28T14:03:00Z"))
        assertEquals(1_790_604_180_123L, IntentPayload.parseIsoMillis("2026-09-28T14:03:00.123456789Z"))
        assertEquals(1_790_604_180_100L, IntentPayload.parseIsoMillis("2026-09-28T14:03:00.1Z"))
        assertEquals(1_790_604_180_123L, IntentPayload.parseIsoMillis("2026-09-28T14:03:00.123456Z"))
        listOf(
            "2026-02-29T00:00:00.000Z",
            "2026-13-01T00:00:00.000Z",
            "2026-09-28T24:00:00.000Z",
            "2026-09-28T14:03:00.000+01:00",
            "2026-09-28 14:03:00Z",
            "",
        ).forEach { assertNull(IntentPayload.parseIsoMillis(it), it) }
    }

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
