// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.types

import xyz.justzappit.evm.math.BigInteger
import kotlin.jvm.JvmInline

/**
 * An account transaction nonce — a monotonic per-account counter. Typed apart from [Wei] amounts
 * and [Gas] unit-counts so the three can't be transposed in transaction assembly.
 */
@JvmInline
value class Nonce(
    val value: BigInteger
) {
    init {
        require(value.signum() >= 0) { "Nonce must be non-negative, got $value" }
    }
}
