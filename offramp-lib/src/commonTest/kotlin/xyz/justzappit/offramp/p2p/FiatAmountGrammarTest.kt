// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the EMVCo tag-54 grammar against `@p2pdotme/sdk`'s `parseAmount`. The exponent case is the
 * one with teeth: `BigDecimal` alone accepts it, and rendering the result allocates gigabytes.
 */
class FiatAmountGrammarTest {
    @Test
    fun `plain decimals are accepted`() {
        assertEquals(0, parsePositiveFiatAmount("1500")?.compareTo(BigDecimal("1500")))
        assertEquals(0, parsePositiveFiatAmount("23.72")?.compareTo(BigDecimal("23.72")))
        assertEquals(0, parsePositiveFiatAmount("  0.05  ")?.compareTo(BigDecimal("0.05")))
    }

    @Test
    fun `an exponent that would allocate gigabytes to render is refused`() {
        assertNull(parsePositiveFiatAmount("1E2000000000"))
        assertNull(parsePositiveFiatAmount("1E5"))
        assertNull(parsePositiveFiatAmount("1e5"))
    }

    @Test
    fun `forms the SDK regex rejects are rejected here too`() {
        val rejected = listOf("+5", ".5", "1.", "1,5", "1.2.3", "0x10", "1_000", "NaN", "Infinity", "١٥")
        rejected.forEach { assertNull(parsePositiveFiatAmount(it), it) }
    }

    @Test
    fun `zero and negatives stay rejected`() {
        listOf("0", "0.0", "-5").forEach { assertNull(parsePositiveFiatAmount(it), it) }
    }
}
