// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReclaimReturnLinkTest {
    @Test
    fun `a valid callback reconstructs the verification route`() {
        val args =
            ReclaimReturnLink.resumeArgs(
                sessionId = "session-123",
                platformName = "x",
                currencyCode = "inr",
            )

        assertEquals(CurrencyCode.Inr, args?.currency)
        assertEquals("session-123", args?.reclaimSessionId)
        assertEquals(SocialPlatform.X.name, args?.reclaimPlatform)
    }

    @Test
    fun `untrusted callback fields fail closed`() {
        assertNull(ReclaimReturnLink.resumeArgs("../session", "X", "INR"))
        assertNull(ReclaimReturnLink.resumeArgs("session-123", "unknown", "INR"))
        assertNull(ReclaimReturnLink.resumeArgs("session-123", "X", "unknown"))
    }
}
