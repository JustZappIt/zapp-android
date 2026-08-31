// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReputationSummaryTest {
    @Test
    fun `a cold wallet cannot buy and is not at the ceiling`() {
        val cold = summary(points = 0, buy = 0)
        assertFalse(cold.canBuy)
        assertFalse(cold.isAtCeiling)
        assertTrue(cold.verified.isEmpty())
    }

    @Test
    fun `at one to one a verification promises its award in dollars`() {
        val s = summary(points = 100, buy = 100_000_000)
        assertEquals(Usdc6.ofMicros(100_000_000L), s.limitGainFor(SocialPlatform.LinkedIn))
        assertEquals(Usdc6.ofMicros(50_000_000L), s.limitGainFor(SocialPlatform.GitHub))
    }

    @Test
    fun `the gain is clamped to the headroom left under the ceiling`() {
        // $350 of $400: LinkedIn's 100 RP cannot buy $100 of limit, only the $50 that remains.
        val s = summary(points = 350, buy = 350_000_000)
        assertEquals(Usdc6.ofMicros(50_000_000L), s.limitGainFor(SocialPlatform.LinkedIn))
    }

    @Test
    fun `at the ceiling nothing is promised`() {
        val s = summary(points = 400, buy = 400_000_000)
        assertTrue(s.isAtCeiling)
        SocialPlatform.entries.forEach { assertNull(s.limitGainFor(it)) }
    }

    @Test
    fun `an already verified platform promises nothing`() {
        val s = summary(points = 100, buy = 100_000_000, verified = setOf(SocialPlatform.LinkedIn))
        assertNull(s.limitGainFor(SocialPlatform.LinkedIn))
        assertEquals(Usdc6.ofMicros(50_000_000L), s.limitGainFor(SocialPlatform.X))
    }

    @Test
    fun `a corridor that pays more limit per point promises more`() {
        // BRL reads (1,3) and pays $3 of limit per RP — measured on Base mainnet, where one wallet
        // at 100 RP holds $100 on INR, $200 on PEN (1,2) and $300 on BRL.
        val brl =
            summary(points = 100, buy = 300_000_000, maxBuy = 800_000_000)
                .copy(
                    currency = CurrencyCode.Brl,
                    rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(1), bigIntegerValueOf(3)),
                )
        assertEquals(Usdc6.ofMicros(300_000_000L), brl.limitGainFor(SocialPlatform.LinkedIn))
        assertEquals(Usdc6.ofMicros(150_000_000L), brl.limitGainFor(SocialPlatform.GitHub))
    }

    @Test
    fun `an unreadable ratio is never guessed at`() {
        val s =
            summary(points = 100, buy = 100_000_000)
                .copy(rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(0), bigIntegerValueOf(0)))
        SocialPlatform.entries.forEach { assertNull(s.limitGainFor(it)) }
    }

    @Test
    fun `a platform the chain awards nothing for promises nothing`() {
        val s = summary(points = 0, buy = 0, awards = emptyMap())
        SocialPlatform.entries.forEach { assertNull(s.limitGainFor(it)) }
    }

    private fun summary(
        points: Long,
        buy: Long,
        maxBuy: Long = 400_000_000L,
        verified: Set<SocialPlatform> = emptySet(),
        awards: Map<SocialPlatform, Long> = MAINNET_AWARDS,
    ) = ReputationSummary(
        currency = CurrencyCode.Inr,
        points = bigIntegerValueOf(points),
        isBlacklisted = false,
        verified = verified,
        awards = awards.mapValues { bigIntegerValueOf(it.value) },
        buyLimit = Usdc6.ofMicros(buy),
        maxBuyLimit = Usdc6.ofMicros(maxBuy),
        rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(1), bigIntegerValueOf(1)),
    )

    private companion object {
        // Measured on Base mainnet 2026-08-29; read on chain in production, fixed here.
        val MAINNET_AWARDS =
            mapOf(
                SocialPlatform.LinkedIn to 100L,
                SocialPlatform.X to 50L,
                SocialPlatform.GitHub to 50L,
                SocialPlatform.Instagram to 50L,
                SocialPlatform.Facebook to 50L,
                SocialPlatform.Binance to 50L,
            )
    }
}
