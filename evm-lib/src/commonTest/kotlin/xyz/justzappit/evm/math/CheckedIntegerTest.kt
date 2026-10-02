// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckedIntegerTest {
    @Test
    fun unsignedLongBoundariesRejectTruncation() {
        for (value in listOf(0L, 1L, Long.MAX_VALUE)) {
            assertEquals(value, bigIntegerValueOf(value).toNonNegativeLongExact())
        }
        for (value in listOf("-1", "9223372036854775808", "18446744073709551616", "18446744073709552616")) {
            assertFailsWith<IllegalArgumentException> { BigInteger(value).toNonNegativeLongExact() }
        }
    }
}
