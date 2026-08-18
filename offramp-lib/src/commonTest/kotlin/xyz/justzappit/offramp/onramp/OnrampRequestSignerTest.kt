// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The signature vector below was produced against the live service and accepted by it, so a
 * regression here fails in CI rather than as an opaque 401 on a phone.
 */
class OnrampRequestSignerTest {
    private val key =
        EvmKeyDerivation.fromPrivateKey(
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318".hexToBytes(),
        )
    private val signer = OnrampRequestSigner(key)

    @Test
    fun `signer address is the key address`() {
        assertEquals("0x2c7536E3605D9C16a7a3D7b1898e529396a65c23", signer.address.checksumHex)
    }

    @Test
    fun `canonical message is newline separated with no trailing newline`() {
        val message = signer.canonicalMessage(NONCE, "POST", "/v1/quote", BODY)

        assertEquals(
            "p2p-onramp-operator\n" +
                "app:zapp\n" +
                "nonce:$NONCE\n" +
                "method:POST\n" +
                "path:/v1/quote\n" +
                "body:$BODY_SHA256",
            message,
        )
        assertTrue(!message.endsWith("\n"))
    }

    @Test
    fun `body hash is lowercase hex sha256 and empty body hashes the empty string`() {
        assertEquals(BODY_SHA256, OnrampRequestSigner.sha256Hex(BODY))
        assertEquals(EMPTY_SHA256, OnrampRequestSigner.sha256Hex(""))
    }

    @Test
    fun `method is uppercased and any query string is excluded from the path`() {
        assertEquals(
            signer.canonicalMessage(NONCE, "POST", "/v1/orders", ""),
            signer.canonicalMessage(NONCE, "post", "/v1/orders?cursor=abc", ""),
        )
    }

    @Test
    fun `signature matches the vector the live service accepted`() {
        assertEquals(EXPECTED_SIGNATURE, signer.sign(NONCE, "POST", "/v1/quote", BODY))
    }

    @Test
    fun `signature is 0x plus 130 hex characters`() {
        val signature = signer.sign(NONCE, "GET", "/v1/orders", "")

        assertTrue(signature.startsWith("0x"))
        assertEquals(SIGNATURE_HEX_LEN, signature.length - 2)
    }

    /**
     * The EIP-191 header carries the byte length, which diverges from `String.length` on any
     * non-ASCII character. Signing the same text under both lengths must not collide, or the trap
     * the header exists to prevent would go undetected.
     */
    @Test
    fun `byte length and char length diverge on non-ascii bodies`() {
        val unicodeBody = """{"note":"₹100 café"}"""

        assertNotEquals(unicodeBody.length, unicodeBody.encodeToByteArray().size)
        assertNotEquals(
            signer.sign(NONCE, "POST", "/v1/quote", unicodeBody),
            signer.sign(NONCE, "POST", "/v1/quote", BODY),
        )
    }

    @Test
    fun `each field changes the signature`() {
        val base = signer.sign(NONCE, "POST", "/v1/quote", BODY)

        assertNotEquals(base, signer.sign(OTHER_NONCE, "POST", "/v1/quote", BODY))
        assertNotEquals(base, signer.sign(NONCE, "GET", "/v1/quote", BODY))
        assertNotEquals(base, signer.sign(NONCE, "POST", "/v1/orders", BODY))
        assertNotEquals(base, signer.sign(NONCE, "POST", "/v1/quote", ""))
        assertNotEquals(base, OnrampRequestSigner(key, "other").sign(NONCE, "POST", "/v1/quote", BODY))
    }

    private companion object {
        const val NONCE = "00000000-0000-4000-8000-000000000000"
        const val OTHER_NONCE = "00000000-0000-4000-8000-000000000001"
        const val BODY = """{"fiatAmount":"100000000","currency":"INR"}"""
        const val BODY_SHA256 = "ba401633f50d9aa66f5f06b59596abe3ba88426b70a6a2c8c6ceeb3187a06841"
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val SIGNATURE_HEX_LEN = 130
        const val EXPECTED_SIGNATURE =
            "0x3f33c9b0c4335e2d9c5dd78e997399186b9fd840c701eed3ee28c46c58f0e4f9" +
                "73569e86adddafc4f63286a7458896eb22bcfa9fab1ec62bc25406036f542e911c"
    }
}
