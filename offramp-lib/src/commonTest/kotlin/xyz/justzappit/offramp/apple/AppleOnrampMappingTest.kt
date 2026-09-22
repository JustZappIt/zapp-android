// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampQuote
import xyz.justzappit.offramp.onramp.OnrampRoute
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Swift boundary is only as good as the strings crossing it. The route travels out with the
 * quote and back with the placement; a failure code is what a Swift reducer switches on; the
 * detail is the one sentence Swift shows in place of its own.
 */
class AppleOnrampMappingTest {
    @Test
    fun `the route crosses as its name and comes back as itself`() {
        OnrampRoute.entries.forEach { route ->
            val apple = quote(route).toApple()
            assertEquals(route.name, apple.route)
            assertEquals(route, apple.toShared().route)
        }
    }

    @Test
    fun `a route this build cannot name is refused, not defaulted`() {
        // Defaulting to DIRECT would place an integrator-sized amount straight on the Diamond,
        // where it reverts on the reputation limit after the user waited on a UserOp.
        val apple = quote(OnrampRoute.INTEGRATOR).toApple().copy(route = "PROXY")
        assertFailsWith<IllegalArgumentException> { apple.toShared() }
    }

    @Test
    fun `the quote round-trips every amount as micros`() {
        val shared = quote(OnrampRoute.DIRECT).toApple().toShared()
        assertEquals(quote(OnrampRoute.DIRECT), shared)
    }

    @Test
    fun `every failure code crosses as its own name`() {
        OnrampFailureCode.entries.forEach { code ->
            val apple = OnrampStatus.Failed(code, OnrampPhase.PLACING, id = null, orderId = null).toApple()
            assertEquals("failed", apple.kind)
            assertEquals(code.name, apple.failureCode)
        }
    }

    @Test
    fun `the service's own sentence crosses beside the code, and only when there is one`() {
        val refused =
            OnrampStatus
                .Failed(
                    code = OnrampFailureCode.SCREENING_REJECTED,
                    phase = OnrampPhase.PLACING,
                    id = null,
                    orderId = null,
                    detail = "new accounts cannot place buy orders at this time",
                ).toApple()
        assertEquals("SCREENING_REJECTED", refused.failureCode)
        assertEquals("new accounts cannot place buy orders at this time", refused.failureDetail)

        val plain =
            OnrampStatus
                .Failed(OnrampFailureCode.NO_MERCHANT, OnrampPhase.AWAITING_MERCHANT, id = "1", orderId = "1")
                .toApple()
        assertNull(plain.failureDetail)
        assertNull(OnrampStatus.AwaitingMerchant("1", "1").toApple().failureDetail)
    }

    @Test
    fun `a transient failure crosses as one the order survives`() {
        // Swift keeps the checkpoint on exactly the codes Kotlin calls transient; the two lists
        // are kept in step by name, so the name is what is pinned here.
        val transient = OnrampFailureCode.entries.filter { it.isTransient }.map { it.name }
        assertEquals(
            listOf("UPSTREAM_FAILED", "OPERATOR_UNAVAILABLE", "NETWORK_UNAVAILABLE", "SETTLEMENT_PENDING"),
            transient,
        )
    }

    private fun quote(route: OnrampRoute) =
        OnrampQuote(
            quoteId = "direct-1",
            currency = CurrencyCode.Inr,
            fiatAmount = Usdc6.ofMicros(1_500_000_000L),
            grossUsdc = Usdc6.ofMicros(14_739_000L),
            feeUsdc = Usdc6.ofMicros(50_000L),
            netUsdc = Usdc6.ofMicros(14_689_000L),
            buyPrice = Usdc6.ofMicros(101_770_000L),
            expiresAtMillis = 1_757_900_000_000L,
            route = route,
        )
}
