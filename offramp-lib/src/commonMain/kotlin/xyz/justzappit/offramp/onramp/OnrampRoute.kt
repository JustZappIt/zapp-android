// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Where a BUY's placement transaction goes. It is the only thing that differs between the two: a
 * proxy-placed order is a normal Diamond order with the buyer as `user` and recipient, so the
 * receipt, the payment and the cancel are handled the same way afterwards.
 */
enum class OnrampRoute {
    /** `placeOrder` on the Diamond from the smart account, sized by reputation. */
    DIRECT,

    /** `buyUsdc` on Zapp's integrator, which places on the Diamond for the caller, sized by the selfie check. */
    INTEGRATOR,
}

/**
 * The two per-order limits one wallet holds, read together so a single decision can be made from
 * them. [integratorOrdersRemaining] is the integrator's daily placement count, a separate gate
 * from its amount, and [integratorPaused] its owner's switch, which its limit view ignores.
 */
data class OnrampRouteLimits(
    val direct: Usdc6,
    val integrator: Usdc6,
    val integratorOrdersRemaining: BigInteger,
    val integratorPaused: Boolean = false,
) {
    /**
     * What the amount screen shows and the gate opens on. Ignores the daily count and the pause
     * on purpose: a selfie wallet that has used today's orders must be told it reached today's
     * limit, not that buying is unavailable, and the same goes for a paused integrator.
     */
    val max: Usdc6 get() = if (integrator > direct) integrator else direct

    /** Reputation first, the selfie as fallback. [netUsdc] is what is placed, and is positive. */
    fun decide(netUsdc: Usdc6): OnrampRouteDecision =
        when {
            netUsdc <= direct -> OnrampRouteDecision.Route(OnrampRoute.DIRECT)
            netUsdc > integrator -> OnrampRouteDecision.OverLimit
            integratorPaused -> OnrampRouteDecision.IntegratorPaused
            integratorOrdersRemaining.signum() > 0 -> OnrampRouteDecision.Route(OnrampRoute.INTEGRATOR)
            else -> OnrampRouteDecision.DailyExhausted(smallerAmountCarried = direct.micros.signum() > 0)
        }
}

sealed interface OnrampRouteDecision {
    data class Route(
        val route: OnrampRoute,
    ) : OnrampRouteDecision

    /**
     * The integrator would carry the amount, but this wallet has placed its orders for today.
     * [smallerAmountCarried] says whether the Diamond still takes a smaller one — the difference
     * between "try a smaller amount" and "try again tomorrow".
     */
    data class DailyExhausted(
        val smallerAmountCarried: Boolean,
    ) : OnrampRouteDecision

    /** The integrator would carry the amount, but its owner has switched it off. */
    data object IntegratorPaused : OnrampRouteDecision

    /** Above both limits. */
    data object OverLimit : OnrampRouteDecision
}
