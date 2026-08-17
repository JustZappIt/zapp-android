// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.math.bigIntegerValueOf
import kotlin.test.Test
import kotlin.test.assertFailsWith

class QrAmountPolicyTest {
    @Test
    fun `accepts the exact amount and both two-percent boundaries`() {
        val placed = bigIntegerValueOf(10_000_000L)
        validateQrAmountAdjustment(placed, bigIntegerValueOf(10_000_000L))
        validateQrAmountAdjustment(placed, bigIntegerValueOf(10_200_000L))
        validateQrAmountAdjustment(placed, bigIntegerValueOf(9_800_000L))
    }

    @Test
    fun `rejects changes beyond two percent`() {
        val placed = bigIntegerValueOf(10_000_000L)
        assertFailsWith<IllegalArgumentException> {
            validateQrAmountAdjustment(placed, bigIntegerValueOf(10_200_001L))
        }
        assertFailsWith<IllegalArgumentException> {
            validateQrAmountAdjustment(placed, bigIntegerValueOf(9_799_999L))
        }
    }

    @Test
    fun `rejects non-positive and over-cap amounts`() {
        val placed = bigIntegerValueOf(100_000_000L)
        assertFailsWith<IllegalArgumentException> {
            validateQrAmountAdjustment(placed, bigIntegerValueOf(0L))
        }
        assertFailsWith<IllegalArgumentException> {
            validateQrAmountAdjustment(placed, bigIntegerValueOf(100_000_001L))
        }
    }
}
