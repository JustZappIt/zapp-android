// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.crypto.Ecies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaymentAddressDecryptorTest {
    @Test
    fun `SDK wrapped ciphertext decrypts to payment address message`() {
        val payload = """{"message":"payer@okhdfc","signature":"0xdeadbeef"}"""
        val ciphertext = Ecies.cipherStringify(Ecies.encryptWithPublicKey(PUB_HEX, payload))

        assertEquals("payer@okhdfc", PaymentAddressDecryptor.decrypt(ciphertext, relay))
    }

    @Test
    fun `legacy eth-crypto JSON envelope decrypts to raw plaintext`() {
        val encrypted = Ecies.encryptWithPublicKey(PUB_HEX, "payer@okhdfc")
        val legacyEnvelope =
            """
            {
              "iv":"${encrypted.iv}",
              "ephemPublicKey":"${encrypted.ephemPublicKey}",
              "ciphertext":"${encrypted.ciphertext}",
              "mac":"${encrypted.mac}"
            }
            """.trimIndent()

        assertEquals("payer@okhdfc", PaymentAddressDecryptor.decrypt(legacyEnvelope, relay))
    }

    @Test
    fun `blank ciphertext is not decryptable`() {
        assertNull(PaymentAddressDecryptor.decrypt("", relay))
    }

    private companion object {
        const val PRIV_HEX = "0x0101010101010101010101010101010101010101010101010101010101010101"
        const val PUB_HEX =
            "1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f" +
                "70beaf8f588b541507fed6a642c5ab42dfdf8120a7f639de5122d47a69a8e8d1"

        val relay = RelayIdentity(privateKeyHex = PRIV_HEX, publicKeyHex = PUB_HEX)
    }
}
