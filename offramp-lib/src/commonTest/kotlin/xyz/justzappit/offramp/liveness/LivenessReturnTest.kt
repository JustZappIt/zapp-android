// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LivenessReturnTest {
    @Test
    fun `the corridor survives the round trip through state`() {
        val state = LivenessReturn.state(NONCE, CurrencyCode.Inr)

        assertEquals("$NONCE.INR", state)
        assertEquals(CurrencyCode.Inr, LivenessReturn(code = "abc", error = null, state = state).currency)
    }

    @Test
    fun `state without a corridor names no route`() {
        assertNull(LivenessReturn.currencyFromState(NONCE))
        assertNull(LivenessReturn.currencyFromState("$NONCE.XXX"))
        assertNull(LivenessReturn.currencyFromState(""))
        // The widget sends no state on an error, so a failed check has no route to rebuild.
        assertNull(LivenessReturn(code = null, error = "cancelled", state = null).currency)
    }

    private companion object {
        const val NONCE = "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d"
    }
}
