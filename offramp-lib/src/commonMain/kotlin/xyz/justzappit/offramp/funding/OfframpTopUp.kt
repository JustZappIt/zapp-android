// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.funding

import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Adds USDC to the reusable Base balance by bridging ZEC, independent of any order. Distinct from
 * [OfframpFunding] (which funds *to* an order amount and short-circuits when already funded): this
 * always bridges the requested [usdc], so the user can deliberately top up their Base balance ahead
 * of paying. Network-toggled in DI — only mainnet has a NEAR route.
 *
 * Resume-safe and idempotent, same contract as [OfframpFunding]: a non-null [resumeHandle] re-polls
 * the already-opened 1-Click deposit address instead of quoting a second bridge, and
 * [onBridgeStarted] fires the instant the deposit address is known (before any ZEC moves) so the
 * caller can persist it first and close the crash-during-deposit double-send window.
 */
fun interface OfframpTopUp {
    suspend fun bridge(
        account: Address,
        usdc: Usdc6,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome
}

/** Testnet/dev: no NEAR route, so Base must be funded manually. Fails fast with an actionable message. */
class NoRouteOfframpTopUp : OfframpTopUp {
    override suspend fun bridge(
        account: Address,
        usdc: Usdc6,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome =
        error("Adding funds to Base via bridge is only available on mainnet. Fund ${account.checksumHex} directly.")
}
