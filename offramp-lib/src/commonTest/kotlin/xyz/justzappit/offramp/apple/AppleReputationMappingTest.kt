// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reclaim.ReclaimFailure
import xyz.justzappit.offramp.reclaim.ReclaimStatus
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.RpPerUsdcLimit
import xyz.justzappit.offramp.reputation.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Swift boundary is only as good as the strings crossing it. Every assertion here pins
 * something a Swift reducer switches on or renders: rename one silently and a screen stops
 * recognising the state it is in, which is not a compile error on either side.
 */
class AppleReputationMappingTest {
    @Test
    fun `platforms cross in award order with LinkedIn first`() {
        val apple = summary(points = 100, buy = 100_000_000).toApple()
        assertEquals(SocialPlatform.entries.map { it.name }, apple.platforms.map { it.id })
        assertEquals("LinkedIn", apple.platforms.first().name)
    }

    @Test
    fun `the id is the routing key and the name is the brand spelling`() {
        val apple = summary(points = 0, buy = 0).toApple()
        val linkedIn = apple.platforms.single { it.id == SocialPlatform.LinkedIn.name }
        assertEquals(SocialPlatform.LinkedIn.name, linkedIn.id)
        assertEquals(SocialPlatform.LinkedIn.onChainName, linkedIn.name)
    }

    @Test
    fun `a cold wallet crosses as locked rather than as a zero limit`() {
        val apple = summary(points = 0, buy = 0).toApple()
        assertFalse(apple.canBuy)
        assertFalse(apple.isAtCeiling)
        assertEquals("0", apple.buyLimitMicros)
        assertEquals(CurrencyCode.Inr.code, apple.currencyCode)
    }

    @Test
    fun `limits cross as micro strings, never as rendered amounts`() {
        val apple = summary(points = 100, buy = 100_000_000).toApple()
        assertEquals("100000000", apple.buyLimitMicros)
        assertEquals("400000000", apple.maxBuyLimitMicros)
        assertEquals("100", apple.points)
    }

    @Test
    fun `a verified platform says nothing about a gain it cannot make`() {
        val apple =
            summary(points = 100, buy = 100_000_000, verified = setOf(SocialPlatform.LinkedIn)).toApple()
        val linkedIn = apple.platforms.single { it.id == SocialPlatform.LinkedIn.name }
        assertTrue(linkedIn.isVerified)
        assertNull(linkedIn.limitGainMicros)
        assertEquals("100", linkedIn.awardPoints)
    }

    @Test
    fun `an unreadable ratio is a null gain, not a zero one`() {
        val apple =
            summary(points = 100, buy = 100_000_000)
                .copy(rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(0), bigIntegerValueOf(0)))
                .toApple()
        assertTrue(apple.platforms.all { it.limitGainMicros == null })
    }

    @Test
    fun `at the ceiling nothing is promised`() {
        val apple = summary(points = 400, buy = 400_000_000).toApple()
        assertTrue(apple.isAtCeiling)
        assertTrue(apple.platforms.all { it.limitGainMicros == null })
    }

    @Test
    fun `the gain crosses clamped to the headroom that is left`() {
        val apple = summary(points = 350, buy = 350_000_000).toApple()
        val linkedIn = apple.platforms.single { it.id == SocialPlatform.LinkedIn.name }
        assertEquals("50000000", linkedIn.limitGainMicros)
    }

    @Test
    fun `the age rule crosses so the row can state it before the user spends five minutes`() {
        val apple = summary(points = 0, buy = 0).toApple()
        val mature = apple.platforms.filter { it.requiresMatureAccount }.map { it.id }
        assertEquals(
            listOf(SocialPlatform.X, SocialPlatform.GitHub, SocialPlatform.Instagram).map { it.name },
            mature,
        )
    }

    @Test
    fun `ready carries the request url alone`() {
        val ready =
            ReclaimStatus
                .Ready(
                    requestUrl = "https://share.reclaimprotocol.org/link/abc",
                    installIntentUrl = "market://details?id=org.reclaimprotocol.app",
                    storeUrl = "https://play.google.com/store/apps/details?id=org.reclaimprotocol.app",
                ).toApple() as AppleReclaimStatus.Ready
        assertEquals("https://share.reclaimprotocol.org/link/abc", ready.requestUrl)
    }

    @Test
    fun `every failure crosses as its own name`() {
        ReclaimFailure.entries.forEach { failure ->
            val mapped = ReclaimStatus.Failed(failure).toApple() as AppleReclaimStatus.Failed
            assertEquals(failure.name, mapped.reason)
        }
        // Nine distinct sentences on the Swift side depend on nine distinct reasons here.
        assertEquals(
            ReclaimFailure.entries.size,
            ReclaimFailure.entries
                .map { it.name }
                .toSet()
                .size,
        )
    }

    @Test
    fun `done carries the summary the chain reported back`() {
        val done =
            ReclaimStatus.Done(summary(points = 100, buy = 100_000_000)).toApple() as AppleReclaimStatus.Done
        assertEquals("100000000", done.summary.buyLimitMicros)
        assertTrue(done.summary.canBuy)
    }

    @Test
    fun `the stages cross as the objects Swift switches on`() {
        assertEquals(AppleReclaimStatus.Preparing, ReclaimStatus.Preparing.toApple())
        assertEquals(AppleReclaimStatus.Verifying, ReclaimStatus.Verifying.toApple())
        assertEquals(AppleReclaimStatus.Submitting, ReclaimStatus.Submitting.toApple())
    }

    private fun summary(
        points: Long,
        buy: Long,
        maxBuy: Long = 400_000_000L,
        verified: Set<SocialPlatform> = emptySet(),
    ) = ReputationSummary(
        currency = CurrencyCode.Inr,
        points = bigIntegerValueOf(points),
        isBlacklisted = false,
        verified = verified,
        awards = MAINNET_AWARDS.mapValues { bigIntegerValueOf(it.value) },
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
