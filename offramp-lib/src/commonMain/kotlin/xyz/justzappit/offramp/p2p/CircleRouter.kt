// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.random.Random

// Epsilon-greedy circle selector, 1:1 port of the SDK's routing.ts.
class CircleRouter(
    private val random: Random = Random.Default,
    private val epsilon: Double = EPSILON,
    private val recoveryScale: Double = RECOVERY_SCALE,
    private val bootstrapMaxWeight: Double = BOOTSTRAP_MAX_WEIGHT,
    private val maxValidationAttempts: Int = MAX_VALIDATION_ATTEMPTS,
) {
    fun circleWeight(c: CircleForRouting): Double {
        val score = c.metrics.score
        return when (c.metrics.status) {
            CircleStatus.PAUSED -> score * recoveryScale
            CircleStatus.BOOTSTRAP -> minOf(score, bootstrapMaxWeight)
            else -> score
        }
    }

    fun filterEligible(circles: List<CircleForRouting>, currency: String): List<CircleForRouting> =
        circles.filter { it.currency.equals(currency, ignoreCase = true) }

    fun selectCircle(eligible: List<CircleForRouting>): CircleForRouting? {
        if (eligible.isEmpty()) return null
        val active = eligible.filter { it.metrics.status == CircleStatus.ACTIVE }

        if (random.nextDouble() < epsilon) {
            return weightedRandomChoice(eligible, eligible.map(::circleWeight))
        }
        if (active.isEmpty()) {
            return weightedRandomChoice(eligible, eligible.map(::circleWeight))
        }
        return weightedRandomChoice(active, active.map { it.metrics.score })
    }

    suspend fun selectCircleForOrder(
        circles: List<CircleForRouting>,
        orderCurrency: String,
        validateCircle: suspend (CircleId) -> Boolean,
    ): CircleId {
        // Genuine failures from validateCircle (RPC, revert) propagate; only a `false` return
        // counts as "circle invalid, try the next one".
        val pool = filterEligible(circles, orderCurrency).toMutableList()
        if (pool.isEmpty()) error("No eligible circles found for currency '$orderCurrency'")

        repeat(maxValidationAttempts) {
            if (pool.isEmpty()) error("No eligible circles found")
            val chosen = selectCircle(pool) ?: error("No eligible circles found")
            if (validateCircle(chosen.id)) return chosen.id
            pool.removeAll { it.id == chosen.id }
        }
        error("Exhausted $maxValidationAttempts validation attempts without a valid circle")
    }

    private fun weightedRandomChoice(
        circles: List<CircleForRouting>,
        weights: List<Double>,
    ): CircleForRouting {
        val total = weights.sum()
        if (total <= 0.0) {
            return circles[random.nextInt(circles.size)]
        }
        var pick = random.nextDouble() * total
        for (i in circles.indices) {
            pick -= weights[i]
            if (pick <= 0.0) return circles[i]
        }
        return circles.last()
    }

    companion object {
        const val EPSILON = 0.25
        const val RECOVERY_SCALE = 0.3
        const val BOOTSTRAP_MAX_WEIGHT = 25.0
        const val MAX_VALIDATION_ATTEMPTS = 3
    }
}
