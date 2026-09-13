// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Calldata fixtures are `cast calldata` output over the same inputs, selectors from `cast sig`. */
class LivenessCallsTest {
    @Test
    fun `submit calldata matches cast, signature as a dynamic tail`() {
        val attestation =
            LivenessAttestation(
                wallet = WALLET,
                nullifier = NULLIFIER.hexToBytes(),
                limit = bigIntegerValueOf(75_000_000L),
                expiry = 1_760_000_000L,
                signature = SIGNATURE.hexToBytes(),
                attestor = ATTESTOR,
            )
        assertEquals(SUBMIT_CALLDATA, LivenessCalls.submitAttestationCalldata(attestation).hex())
    }

    @Test
    fun `read calldata matches cast for every integrator view`() {
        assertEquals("0x0db065f4$WALLET_WORD", LivenessCalls.verifiedCalldata(WALLET).hex())
        assertEquals("0xa55c8357$WALLET_WORD", LivenessCalls.effectiveLimitCalldata(WALLET).hex())
        assertEquals("0x4cec43c1", LivenessCalls.tierCapCalldata().hex())
    }

    @Test
    fun `return words decode to a flag and to micro-USDC`() {
        assertTrue(LivenessCalls.decodeBool(word(1).hexToBytes()))
        assertFalse(LivenessCalls.decodeBool(word(0).hexToBytes()))
        assertEquals(Usdc6.ofMicros(75_000_000L), LivenessCalls.decodeUsdc6(word(75_000_000L).hexToBytes()))
    }

    @Test
    fun `truncated return data throws rather than decoding as zero`() {
        // A short read must fail loudly: a silent 0 shows a verified user as unverified.
        assertFailsWith<IllegalArgumentException> { LivenessCalls.decodeBool(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { LivenessCalls.decodeUsdc6(ByteArray(31)) }
    }

    private fun ByteArray.hex(): String = "0x" + toHex()

    private fun word(value: Long): String = value.toString(16).padStart(64, '0')

    private companion object {
        val WALLET: Address = Address.parse("0x448f857ea117138e85d062c6ce89e90a337874d6")
        val ATTESTOR: Address = Address.parse("0x000000000000000000000000000000000000dEaD")
        const val WALLET_WORD = "000000000000000000000000448f857ea117138e85d062c6ce89e90a337874d6"
        const val NULLIFIER = "0x1111111111111111111111111111111111111111111111111111111111111111"
        val SIGNATURE = "0x" + "ab".repeat(64) + "1b"

        val SUBMIT_CALLDATA =
            "0x2bd54ab8" +
                "1111111111111111111111111111111111111111111111111111111111111111" +
                "00000000000000000000000000000000000000000000000000000000047868c0" +
                "0000000000000000000000000000000000000000000000000000000068e77800" +
                "0000000000000000000000000000000000000000000000000000000000000080" +
                "0000000000000000000000000000000000000000000000000000000000000041" +
                "ab".repeat(64) + "1b" + "00".repeat(31)
    }
}
