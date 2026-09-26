// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.IdentityCheck
import xyz.justzappit.offramp.reputation.ReputationCalls
import xyz.justzappit.offramp.reputation.passportCountry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityAttestationTest {
    @Test
    fun `the widget's flat attestation parses, numbers and strings alike`() {
        val attestation =
            IdentityAttestation.fromJson(
                json(
                    """{"nullifier":"0x${"11".repeat(32)}","limit":"25","expiry":1760000000,""" +
                        """"signature":"0x${"ab".repeat(65)}"}""",
                ),
            )

        assertEquals(bigIntegerValueOf(25L), attestation.limit)
        assertEquals(bigIntegerValueOf(1_760_000_000L), attestation.expiry)
        assertEquals("11".repeat(32), attestation.nullifier.toHex())
    }

    @Test
    fun `anything the contract could not consume is refused`() {
        assertFailsWith<IllegalArgumentException> {
            IdentityAttestation.fromJson(
                json("""{"nullifier":"0x11","limit":0,"expiry":1,"signature":"0x${"ab".repeat(65)}"}"""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IdentityAttestation.fromJson(json("""{"nullifier":"0x${"11".repeat(32)}","limit":0,"expiry":1}"""))
        }
    }

    @Test
    fun `each check submits to its own function with the same four arguments`() {
        val attestation =
            IdentityAttestation(
                nullifier = ByteArray(IdentityAttestation.NULLIFIER_BYTES),
                limit = bigIntegerValueOf(0L),
                expiry = bigIntegerValueOf(1L),
                signature = ByteArray(IdentityAttestation.SIGNATURE_BYTES),
            )

        // `cast sig` of each signature.
        assertEquals(
            "2bd54ab8",
            ReputationCalls
                .submitIdentityAttestationCalldata(IdentityCheck.Liveness, attestation)
                .copyOfRange(0, 4)
                .toHex(),
        )
        assertEquals(
            "6d692e79",
            ReputationCalls
                .submitIdentityAttestationCalldata(IdentityCheck.Passport, attestation)
                .copyOfRange(0, 4)
                .toHex(),
        )
    }

    @Test
    fun `every corridor has a passport country, and only india goes without liveness`() {
        CurrencyCode.entries.forEach { currency ->
            assertTrue(currency.passportCountry?.length == 2, "$currency")
            assertEquals(currency != CurrencyCode.Inr, IdentityCheck.Liveness.isOfferedIn(currency), "$currency")
        }
        assertFalse(IdentityCheck.Liveness.isOfferedIn(CurrencyCode.Inr))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
