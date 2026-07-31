// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.bigIntegerValueOf
import kotlin.test.Test
import kotlin.test.assertEquals

class Usdc6Test {
    @Test
    fun `ofWhole converts whole tokens to micros`() {
        assertEquals(bigIntegerValueOf(5_500_000), Usdc6.ofWhole(BigDecimal("5.50")).micros)
    }

    @Test
    fun `ofWhole rounds half-up at the sixth decimal instead of truncating`() {
        // 1.0000005 → 1_000_000.5 micros → rounds up to 1_000_001 (truncation would give 1_000_000).
        assertEquals(bigIntegerValueOf(1_000_001), Usdc6.ofWhole(BigDecimal("1.0000005")).micros)
        // 1.0000004 → rounds down.
        assertEquals(bigIntegerValueOf(1_000_000), Usdc6.ofWhole(BigDecimal("1.0000004")).micros)
    }
}
