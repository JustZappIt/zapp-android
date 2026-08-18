// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/** Contract-independent client safety limits applied both when quoting and when committing a QR. */
object P2pOrderLimits {
    val MAX_ORDER: Usdc6 = Usdc6.ofMicros(100_000_000L)

    const val QR_AMOUNT_TOLERANCE_PERCENT: Long = 2L
}
