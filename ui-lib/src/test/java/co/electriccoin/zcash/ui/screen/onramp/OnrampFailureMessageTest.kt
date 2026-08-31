// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OnrampFailureMessageTest {
    @Test
    fun `nothing shown after a payment claims the money has not moved`() {
        // onramp_error_backend_unavailable ends "Your money has not moved." A dropped paidBuyOrder
        // arrives as UPSTREAM_FAILED, and telling that user nothing moved invites a second transfer.
        for (phase in OnrampPhase.entries.filter { it.hasSentFiat }) {
            for (code in UNREACHABLE) {
                assertNotEquals(
                    BACKEND_UNAVAILABLE,
                    onrampFailureMessage(code, phase),
                    "$code in $phase must not claim the money has not moved",
                )
            }
        }
    }

    @Test
    fun `an order that never reached payment still reports an unreachable exchange`() {
        assertEquals(
            BACKEND_UNAVAILABLE,
            onrampFailureMessage(OnrampFailureCode.NETWORK_UNAVAILABLE, OnrampPhase.AWAITING_MERCHANT),
        )
        // No order at all: a quote that would not price.
        assertEquals(
            BACKEND_UNAVAILABLE,
            onrampFailureMessage(OnrampFailureCode.NETWORK_UNAVAILABLE, phase = null),
        )
    }

    private companion object {
        val BACKEND_UNAVAILABLE = stringRes(R.string.onramp_error_backend_unavailable)

        val UNREACHABLE =
            listOf(
                OnrampFailureCode.UPSTREAM_FAILED,
                OnrampFailureCode.OPERATOR_UNAVAILABLE,
                OnrampFailureCode.NETWORK_UNAVAILABLE,
            )
    }
}
