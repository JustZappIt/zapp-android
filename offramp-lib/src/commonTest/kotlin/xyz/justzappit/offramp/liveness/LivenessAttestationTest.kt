// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The JSON is the `attestation` object of `POST /v1/widget/result`, as the verifier shapes it. */
class LivenessAttestationTest {
    @Test
    fun `parses the widget result attestation`() {
        val attestation = LivenessAttestation.fromJson(Json.parseToJsonElement(attestationJson()).jsonObject)

        assertEquals(Address.parse(WALLET), attestation.wallet)
        assertEquals(NULLIFIER.removePrefix("0x"), attestation.nullifier.toHex())
        assertEquals("75000000", attestation.limit.toString())
        assertEquals(1_760_000_000L, attestation.expiry)
        assertEquals(SIGNATURE.removePrefix("0x"), attestation.signature.toHex())
        assertEquals(Address.parse(ATTESTOR), attestation.attestor)
    }

    @Test
    fun `a signature the contract cannot recover from is refused`() {
        // ecrecover needs the v byte; a 64-byte signature is a compact one this contract never accepts.
        assertFailsWith<IllegalArgumentException> {
            LivenessAttestation.fromJson(
                Json.parseToJsonElement(attestationJson(signature = "0x" + "ab".repeat(64))).jsonObject,
            )
        }
    }

    @Test
    fun `a message missing its wallet is refused`() {
        val json = attestationJson().replace("\"wallet\": \"$WALLET\",", "")
        assertFailsWith<IllegalArgumentException> {
            LivenessAttestation.fromJson(Json.parseToJsonElement(json).jsonObject)
        }
    }

    private fun attestationJson(signature: String = SIGNATURE): String =
        """
        {
          "signature": "$signature",
          "digest": "0x5b3d0e5b5a6d2a4a1c0d0f8e7b6a5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b",
          "domain": {
            "name": "ZappCheckoutIntegrator",
            "version": "1",
            "chainId": 84532,
            "verifyingContract": "0x689D0507EAAD58ba317F9CbE3eDd1b0822132934"
          },
          "message": {
            "wallet": "$WALLET",
            "nullifier": "$NULLIFIER",
            "limit": 75000000,
            "expiry": 1760000000
          },
          "attestor": "$ATTESTOR",
          "nullifier": "$NULLIFIER"
        }
        """.trimIndent()

    private companion object {
        const val WALLET = "0x448f857ea117138e85d062c6ce89e90a337874d6"
        const val ATTESTOR = "0x000000000000000000000000000000000000dEaD"
        const val NULLIFIER = "0x1111111111111111111111111111111111111111111111111111111111111111"
        val SIGNATURE = "0x" + "ab".repeat(64) + "1b"
    }
}
