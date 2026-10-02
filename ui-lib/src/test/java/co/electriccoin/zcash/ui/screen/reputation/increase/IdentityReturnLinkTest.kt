// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.IdentityCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityReturnLinkTest {
    @Test
    fun `a successful callback carries the code and rebuilds the route`() {
        val ret = parse(code = " $CODE ", error = null, state = "$NONCE.INR")

        assertEquals(CODE, ret?.code)
        assertNull(ret?.error)
        assertEquals(CurrencyCode.Inr, ret?.currency)
    }

    @Test
    fun `a failed callback carries the reason and no route`() {
        val ret = parse(code = null, error = "duplicate_person", state = null)

        assertNull(ret?.code)
        assertEquals("duplicate_person", ret?.error)
        assertNull(ret?.currency)
    }

    @Test
    fun `untrusted callback fields fail closed`() {
        assertNull(parse(code = null, error = null, state = "$NONCE.INR"))
        assertNull(parse(code = "../$CODE", error = null, state = null))
        assertNull(parse(code = "a".repeat(129), error = null, state = null))
        assertNull(parse(code = null, error = "not live!", state = null))
        // A state that does not parse is dropped, not the whole return: the code is still the
        // only copy of the result, and a live run checks state against its own.
        assertNull(parse(code = CODE, error = null, state = "$NONCE.INR?x")?.state)
    }

    @Test
    fun `each check has its own host and the host names the check`() {
        assertEquals("zcash://liveness-return", IdentityReturnLink.url(IdentityCheck.Liveness))
        assertEquals("zcash://kyc-return", IdentityReturnLink.url(IdentityCheck.Passport))
        assertEquals(IdentityCheck.Passport, IdentityReturnLink.checkForHost("KYC-RETURN"))
        assertNull(IdentityReturnLink.checkForHost("reclaim-return"))
        assertNull(IdentityReturnLink.checkForHost(null))
    }

    private fun parse(
        code: String?,
        error: String?,
        state: String?,
    ) = IdentityReturnLink.parse(IdentityCheck.Liveness, code, error, state)

    private companion object {
        const val CODE = "kX9v_2Jq-7Lm3ZfQw8RtYbN4cH6sD1eA0PoIuGhVjKl"
        const val NONCE = "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d"
    }
}
