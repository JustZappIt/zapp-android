// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
import kotlin.jvm.JvmInline

@Serializable
data class CircleForRouting(
    val circleId: String,
    val currency: String,
    val metrics: CircleMetrics,
) {
    /** The circle's on-chain numeric id, parsed once from the subgraph's decimal string. */
    val id: CircleId get() = CircleId(BigInteger(circleId))
}

/** An on-chain circle identifier. Typed so it can't be confused with an order id or amount. */
@JvmInline
value class CircleId(
    val value: BigInteger
)

enum class CircleStatus {
    PAUSED,
    BOOTSTRAP,
    ACTIVE,
    UNKNOWN;

    companion object {
        /** Maps the subgraph's status string; an unrecognised value is [UNKNOWN], never a crash. */
        fun fromWire(wire: String): CircleStatus =
            entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: UNKNOWN
    }
}

@Serializable
data class CircleMetrics(
    // Subgraph emits BigDecimal-as-string; parsed into a Double via [score].
    val circleScore: String,
    val circleStatus: String,
    val scoreState: CircleScoreState,
) {
    val score: Double get() = circleScore.toDoubleOrNull() ?: 0.0
    val status: CircleStatus get() = CircleStatus.fromWire(circleStatus)
}

@Serializable
data class CircleScoreState(
    val activeMerchantsCount: String,
)
