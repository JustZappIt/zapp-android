// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A resumed order has no caller left to say which corridor it belongs to, so the driver reads it
 * back off the order's own `bytes32` currency word. Round-tripping that word is what stands between
 * a resumed INR order and a payment intent built in the wrong currency.
 */
class DirectOnrampCorridorTest {
    @Test
    fun `every corridor's bytes32 word round-trips back to its own code`() {
        CurrencyCode.entries.forEach { currency ->
            val word = "0x" + AbiEncoder.bytes32String(currency.code).value.toHex()
            assertEquals(currency, corridorFromBytes32(word), "round trip failed for ${currency.code}")
        }
    }

    @Test
    fun `the INR word is the one the chain really stores`() {
        // Read from a live order on Base mainnet: "INR", NUL-padded to 32 bytes.
        assertEquals(
            CurrencyCode.Inr,
            corridorFromBytes32("0x494e520000000000000000000000000000000000000000000000000000000000"),
        )
    }

    @Test
    fun `a corridor this app does not serve decodes to nothing, not to a default`() {
        // MEX is a real p2p market that Zapp deliberately does not carry.
        val word = "0x" + AbiEncoder.bytes32String("MEX").value.toHex()
        assertNull(corridorFromBytes32(word))
    }
}
