// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.types

import kotlin.jvm.JvmInline
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.times

/**
 * A gas-unit count (a gas limit or estimate). Typed apart from [Wei] (price-per-gas / value) and
 * [Nonce] so fee math can't accidentally treat a gas count as a wei amount.
 */
@JvmInline
value class Gas(
    val value: BigInteger
) {
    init {
        require(value.signum() >= 0) { "Gas must be non-negative, got $value" }
    }

    operator fun times(scalar: BigInteger): Gas = Gas(value * scalar)

    operator fun div(scalar: BigInteger): Gas = Gas(value / scalar)
}
