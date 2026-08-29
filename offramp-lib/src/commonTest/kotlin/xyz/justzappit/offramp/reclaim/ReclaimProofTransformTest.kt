// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import kotlinx.serialization.json.Json
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The proof body is Reclaim's, and the claim fields below are the ones that really appear in
 * `socialVerify` tx `0xe2c1e551…f792ac` on Base mainnet — so this pins the split against a claim
 * the contract has already accepted, not against our reading of a schema.
 */
class ReclaimProofTransformTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `claimData splits into the hashed half and the signed half`() {
        val status = json.decodeFromString(ReclaimSessionStatus.serializer(), REAL_SESSION)
        val proofs = requireNotNull(status.session).proofs
        assertEquals(1, proofs.size)

        val onChain = ReclaimProofTransform.toOnChain(proofs.first())
        // ClaimInfo: what the verifier re-hashes.
        assertEquals("http", onChain.provider)
        assertTrue(onChain.parameters.contains("x.com"))
        assertTrue(onChain.context.contains("contextAddress"))
        // CompleteClaimData: what it recovers the attestor signature against.
        assertEquals(
            "df3367a28dfb087c7bfd98cf3919dd60e7a92fd1e355abccf48f7dc789cf4555",
            onChain.identifier.toHex(),
        )
        assertEquals(Address.parse("0xdb529A7486D971101c0BA1Ec3E389Dac4BE5a61F"), onChain.owner)
        assertEquals(1_788_011_447L, onChain.timestampS)
        assertEquals(1L, onChain.epoch)
        assertEquals(1, onChain.signatures.size)
        assertEquals(SIGNATURE_BYTES, onChain.signatures.first().size)
    }

    @Test
    fun `the claim owner is the attestor's, not the wallet being verified`() {
        // The wallet that gets the reputation is named in the *context*; the claim's owner is the
        // ephemeral key that produced the claim. Swapping them verifies nothing and reverts late.
        val status = json.decodeFromString(ReclaimSessionStatus.serializer(), REAL_SESSION)
        val onChain = ReclaimProofTransform.toOnChain(requireNotNull(status.session).proofs.first())
        assertTrue(onChain.context.contains("0x448f857Ea117138E85D062C6Ce89E90A337874d6"))
        assertEquals(Address.parse("0xdb529A7486D971101c0BA1Ec3E389Dac4BE5a61F"), onChain.owner)
    }

    @Test
    fun `an empty publicData is the provider saying the account is too new`() {
        val status = json.decodeFromString(ReclaimSessionStatus.serializer(), CRITERIA_NOT_MET_SESSION)
        val proofs = requireNotNull(status.session).proofs
        // Reclaim reports this as a *successful* session carrying a proof — not as an error.
        assertTrue(proofs.isNotEmpty())
        assertTrue(ReclaimProofTransform.isCriteriaNotMet(proofs))
    }

    @Test
    fun `a real proof is not mistaken for a criteria failure`() {
        val status = json.decodeFromString(ReclaimSessionStatus.serializer(), REAL_SESSION)
        assertFalse(ReclaimProofTransform.isCriteriaNotMet(requireNotNull(status.session).proofs))
    }

    @Test
    fun `a session with no proofs is neither a failure nor a pass`() {
        val status = json.decodeFromString(ReclaimSessionStatus.serializer(), EMPTY_SESSION)
        assertFalse(ReclaimProofTransform.isCriteriaNotMet(requireNotNull(status.session).proofs))
    }

    private companion object {
        const val SIGNATURE_BYTES = 65

        const val REAL_SESSION = """
        {
          "session": {
            "statusV2": "PROOF_GENERATION_SUCCESS",
            "proofs": [
              {
                "identifier": "0xdf3367a28dfb087c7bfd98cf3919dd60e7a92fd1e355abccf48f7dc789cf4555",
                "claimData": {
                  "provider": "http",
                  "parameters": "{\"url\":\"https://x.com/i/api/graphql\",\"method\":\"GET\"}",
                  "owner": "0xdb529a7486d971101c0ba1ec3e389dac4be5a61f",
                  "timestampS": 1788011447,
                  "context": "{\"contextAddress\":\"0x448f857Ea117138E85D062C6Ce89E90A337874d6\",\"contextMessage\":\"Social verification for X\",\"reclaimSessionId\":\"f721543b42\"}",
                  "identifier": "0xdf3367a28dfb087c7bfd98cf3919dd60e7a92fd1e355abccf48f7dc789cf4555",
                  "epoch": 1
                },
                "signatures": [
                  "0x24c4b4521dbc34f0096d759f992e01e8cafd803b65d055756d25ff51f4ccb0572ab54a9479e77682bcd2c72ef450b71d92d4b452367f45eee6edd37be444d3341b"
                ],
                "witnesses": [{ "id": "0x244897572368eadf65bfbc5aec98d8e5443a9072", "url": "wss://attestor.reclaimprotocol.org/ws" }],
                "publicData": { "followers_count": "10", "Year": "2018" }
              }
            ]
          }
        }
        """

        const val CRITERIA_NOT_MET_SESSION = """
        {
          "session": {
            "statusV2": "PROOF_GENERATION_SUCCESS",
            "proofs": [
              {
                "identifier": "0xdf3367a28dfb087c7bfd98cf3919dd60e7a92fd1e355abccf48f7dc789cf4555",
                "claimData": {
                  "provider": "http",
                  "parameters": "{}",
                  "owner": "0xdb529a7486d971101c0ba1ec3e389dac4be5a61f",
                  "timestampS": 1788011447,
                  "context": "{}",
                  "identifier": "0xdf3367a28dfb087c7bfd98cf3919dd60e7a92fd1e355abccf48f7dc789cf4555",
                  "epoch": 1
                },
                "signatures": ["0x24c4"],
                "publicData": {}
              }
            ]
          }
        }
        """

        const val EMPTY_SESSION = """{ "session": { "statusV2": "PENDING", "proofs": [] } }"""
    }
}
