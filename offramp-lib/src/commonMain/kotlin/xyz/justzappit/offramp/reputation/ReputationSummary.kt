// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.minus
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * One address's standing on the p2p.me exchange, as the chain reports it: reputation and its
 * blacklist flag from the ReputationManager, the limits it currently buys from the Diamond.
 *
 * Every field here is read, never computed. The Diamond's [buyLimit] is the effective number and
 * the only one any screen may show — deriving it from [points] would put a second, disagreeing
 * answer in front of the user the moment the exchange changes its own arithmetic.
 */
data class ReputationSummary(
    val currency: CurrencyCode,
    val points: BigInteger,
    val isBlacklisted: Boolean,
    val verified: Set<SocialPlatform>,
    /** Reputation each platform awards, read from the RM — the §3.1 table is config, not a constant. */
    val awards: Map<SocialPlatform, BigInteger>,
    val buyLimit: Usdc6,
    val maxBuyLimit: Usdc6,
    val rpPerUsdc: RpPerUsdcLimit,
) {
    /**
     * A cold wallet's buy limit is $0 and it cannot place a BUY of any size. Cashing out is never
     * gated by reputation, so this must never read as a wallet-wide lock.
     */
    val canBuy: Boolean get() = buyLimit.micros.signum() > 0

    /** At the exchange's ceiling for this corridor: further verifications buy nothing. */
    val isAtCeiling: Boolean get() = buyLimit >= maxBuyLimit

    fun award(platform: SocialPlatform): BigInteger = awards[platform] ?: bigIntegerZero

    /**
     * The buy limit [platform] would actually add, or null when we cannot state it honestly.
     *
     * Null when the platform is already verified, when the corridor's RP-to-limit ratio is
     * unreadable, and at the ceiling, where the true answer is zero. Clamped to the remaining
     * headroom otherwise, so the last verification before the ceiling promises what is left
     * rather than its full award.
     */
    @Suppress("ReturnCount")
    fun limitGainFor(platform: SocialPlatform): Usdc6? {
        if (platform in verified) return null
        val headroom = maxBuyLimit.micros - buyLimit.micros
        if (headroom.signum() <= 0) return null
        val gain = rpPerUsdc.limitMicrosFor(award(platform)) ?: return null
        if (gain.signum() <= 0) return null
        return Usdc6(if (gain > headroom) headroom else gain)
    }
}
