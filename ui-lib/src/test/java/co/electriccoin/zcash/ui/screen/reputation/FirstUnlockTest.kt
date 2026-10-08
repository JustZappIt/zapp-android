// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.RpPerUsdcLimit
import xyz.justzappit.offramp.reputation.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the figure the locked Reputation screen leads with: the most one verification would add,
 * counted only over accounts Raise my limit actually offers in the buyer's corridor.
 */
class FirstUnlockTest {
    @Test
    fun `leads with the most valuable account offered`() {
        val summary = locked(mapOf(SocialPlatform.X to 50L, SocialPlatform.LinkedIn to 100L))

        assertEquals(usd(100), summary.bestSingleUnlock(CurrencyCode.Inr))
    }

    @Test
    fun `an INR buyer is never promised Binance's award`() {
        val summary = locked(mapOf(SocialPlatform.Binance to 500L, SocialPlatform.X to 50L))

        assertEquals(usd(50), summary.bestSingleUnlock(CurrencyCode.Inr))
        assertFalse(SocialPlatform.Binance in offeredPlatforms(CurrencyCode.Inr))
    }

    @Test
    fun `other corridors are offered Binance`() {
        val summary = locked(mapOf(SocialPlatform.Binance to 500L, SocialPlatform.X to 50L))

        assertEquals(usd(500), summary.bestSingleUnlock(CurrencyCode.Brl))
        assertTrue(SocialPlatform.Binance in offeredPlatforms(CurrencyCode.Brl))
    }

    @Test
    fun `an already verified account is not promised again`() {
        val summary =
            locked(mapOf(SocialPlatform.X to 50L, SocialPlatform.LinkedIn to 100L))
                .copy(verified = setOf(SocialPlatform.LinkedIn))

        assertEquals(usd(50), summary.bestSingleUnlock(CurrencyCode.Inr))
    }

    @Test
    fun `an unreadable ratio promises nothing`() {
        val summary =
            locked(mapOf(SocialPlatform.LinkedIn to 100L))
                .copy(rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(0L), bigIntegerValueOf(1L)))

        assertNull(summary.bestSingleUnlock(CurrencyCode.Inr))
    }

    /** A cold wallet: $0 buy limit, RP worth $1 each, a $1,000 ceiling. */
    private fun locked(awards: Map<SocialPlatform, Long>) =
        ReputationSummary(
            currency = CurrencyCode.Inr,
            points = bigIntegerValueOf(0L),
            isBlacklisted = false,
            verified = emptySet(),
            awards = awards.mapValues { bigIntegerValueOf(it.value) },
            buyLimit = Usdc6.ZERO,
            maxBuyLimit = usd(1_000),
            rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(1L), bigIntegerValueOf(1L)),
        )

    private fun usd(whole: Long) = Usdc6.ofMicros(whole * 1_000_000L)
}
