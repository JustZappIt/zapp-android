// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LivenessReturnLinkTest {
    @Test
    fun `a successful callback carries the code and rebuilds the route`() {
        val ret = LivenessReturnLink.parse(code = " $CODE ", error = null, state = "$NONCE.INR")

        assertEquals(CODE, ret?.code)
        assertNull(ret?.error)
        assertEquals(CurrencyCode.Inr, ret?.currency)
    }

    @Test
    fun `a failed callback carries the reason and no route`() {
        val ret = LivenessReturnLink.parse(code = null, error = "duplicate_person", state = null)

        assertNull(ret?.code)
        assertEquals("duplicate_person", ret?.error)
        assertNull(ret?.currency)
    }

    @Test
    fun `untrusted callback fields fail closed`() {
        assertNull(LivenessReturnLink.parse(code = null, error = null, state = "$NONCE.INR"))
        assertNull(LivenessReturnLink.parse(code = "../$CODE", error = null, state = null))
        assertNull(LivenessReturnLink.parse(code = "a".repeat(129), error = null, state = null))
        assertNull(LivenessReturnLink.parse(code = null, error = "not live!", state = null))
        // A state that does not parse is dropped, not the whole return: the code is still the
        // only copy of the result, and a live run checks state against its own.
        assertNull(LivenessReturnLink.parse(code = CODE, error = null, state = "$NONCE.INR?x")?.state)
    }

    private companion object {
        const val CODE = "kX9v_2Jq-7Lm3ZfQw8RtYbN4cH6sD1eA0PoIuGhVjKl"
        const val NONCE = "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d"
    }
}
