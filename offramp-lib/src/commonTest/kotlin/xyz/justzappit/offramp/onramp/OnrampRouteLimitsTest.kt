// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reputation first, the selfie as fallback — and the daily count only ever decides between the
 * integrator and a refusal, never between the two routes.
 */
class OnrampRouteLimitsTest {
    @Test
    fun `an amount the diamond covers goes direct, even when the integrator would too`() {
        val limits = limits(direct = 50, integrator = 20, remaining = 5)

        assertEquals(direct, limits.decide(usd(15)))
        // Inclusive on the boundary: the Diamond's own check is `amount > limit`.
        assertEquals(direct, limits.decide(usd(50)))
    }

    @Test
    fun `an amount only the integrator covers goes through it`() {
        val limits = limits(direct = 0, integrator = 20, remaining = 5)

        assertEquals(integrator, limits.decide(usd(15)))
        assertEquals(integrator, limits.decide(usd(20)))
    }

    @Test
    fun `an exhausted daily count refuses only what the diamond cannot carry`() {
        assertEquals(
            OnrampRouteDecision.DailyExhausted(smallerAmountCarried = false),
            limits(direct = 0, integrator = 20, remaining = 0).decide(usd(15)),
        )
        // With a Diamond limit of its own, a smaller amount still goes through today — and the
        // refusal has to say so rather than "tomorrow".
        assertEquals(
            OnrampRouteDecision.DailyExhausted(smallerAmountCarried = true),
            limits(direct = 10, integrator = 20, remaining = 0).decide(usd(15)),
        )
        // The direct route has no daily count of its own to run out of.
        assertEquals(direct, limits(direct = 50, integrator = 20, remaining = 0).decide(usd(15)))
    }

    @Test
    fun `a paused integrator refuses what only it would carry, ahead of its daily count`() {
        val paused = limits(direct = 0, integrator = 20, remaining = 5, paused = true)

        assertEquals(OnrampRouteDecision.IntegratorPaused, paused.decide(usd(15)))
        // Its limit still shows: the wallet holds it, the switch is what is off.
        assertEquals(usd(20), paused.max)
        // The pause is the integrator's alone.
        assertEquals(direct, limits(direct = 50, integrator = 20, remaining = 5, paused = true).decide(usd(15)))
        assertEquals(OnrampRouteDecision.OverLimit, paused.decide(usd(25)))
    }

    @Test
    fun `above both limits is over the limit, whatever the daily count says`() {
        assertEquals(OnrampRouteDecision.OverLimit, limits(direct = 10, integrator = 20, remaining = 5).decide(usd(25)))
        assertEquals(OnrampRouteDecision.OverLimit, limits(direct = 10, integrator = 20, remaining = 0).decide(usd(25)))
        // Two zero limits: nothing positive fits, so a cold wallet is never routed anywhere.
        assertEquals(OnrampRouteDecision.OverLimit, limits(direct = 0, integrator = 0, remaining = 5).decide(usd(1)))
    }

    @Test
    fun `the shown ceiling is the higher limit and ignores the daily count`() {
        assertEquals(usd(50), limits(direct = 50, integrator = 20, remaining = 5).max)
        assertEquals(usd(20), limits(direct = 0, integrator = 20, remaining = 5).max)
        // A selfie wallet that used today's orders still shows $20, so it reads "today's limit
        // reached" rather than "unavailable".
        assertEquals(usd(20), limits(direct = 0, integrator = 20, remaining = 0).max)
        assertEquals(Usdc6.ZERO, limits(direct = 0, integrator = 0, remaining = 5).max)
    }

    private val direct = OnrampRouteDecision.Route(OnrampRoute.DIRECT)
    private val integrator = OnrampRouteDecision.Route(OnrampRoute.INTEGRATOR)

    private fun usd(whole: Long): Usdc6 = Usdc6.ofMicros(whole * MICROS_PER_USDC)

    private fun limits(direct: Long, integrator: Long, remaining: Long, paused: Boolean = false) =
        OnrampRouteLimits(
            direct = usd(direct),
            integrator = usd(integrator),
            integratorOrdersRemaining = if (remaining == 0L) bigIntegerZero else bigIntegerValueOf(remaining),
            integratorPaused = paused,
        )

    private companion object {
        const val MICROS_PER_USDC = 1_000_000L
    }
}
