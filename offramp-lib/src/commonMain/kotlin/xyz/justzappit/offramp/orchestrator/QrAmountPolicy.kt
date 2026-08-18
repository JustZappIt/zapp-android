// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.times
import xyz.justzappit.offramp.p2p.P2pOrderLimits

internal fun validateQrAmountAdjustment(placedMicros: BigInteger, updatedMicros: BigInteger) {
    require(placedMicros.signum() > 0) { "Placed order amount must be positive" }
    require(updatedMicros.signum() > 0) { "Scanned QR amount must be positive" }
    require(updatedMicros <= P2pOrderLimits.MAX_ORDER.micros) {
        "Scanned QR amount exceeds the " +
            "${P2pOrderLimits.MAX_ORDER.toDisplayString(stripTrailingZeros = true)} USDC order limit"
    }
    val delta =
        if (updatedMicros >= placedMicros) {
            updatedMicros - placedMicros
        } else {
            placedMicros - updatedMicros
        }
    require(
        delta * bigIntegerValueOf(100L) <=
            placedMicros * bigIntegerValueOf(P2pOrderLimits.QR_AMOUNT_TOLERANCE_PERCENT),
    ) {
        "Scanned QR amount differs from the confirmed order by more than " +
            "${P2pOrderLimits.QR_AMOUNT_TOLERANCE_PERCENT}%"
    }
}
