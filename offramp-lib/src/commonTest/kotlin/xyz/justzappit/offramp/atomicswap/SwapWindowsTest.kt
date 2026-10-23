// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwapWindowsTest {
    @Test
    fun atEveryMomentAtMostOneSideMayReveal() {
        val stages = listOf(SwapStage.OPEN, SwapStage.READY)
        val moments = listOf(T0 - 1, T0, T1 - 1, T1, LATE - 1, LATE, LATE + 1)
        for (reverse in listOf(false, true)) {
            for (stage in stages) {
                for (now in moments) {
                    val claim = WINDOWS.window(Reveal.CLAIM, reverse, stage, T0, T1, now)
                    val refund = WINDOWS.window(Reveal.REFUND, reverse, stage, T0, T1, now)
                    assertTrue(claim == Window.Shut || refund == Window.Shut, "reverse $reverse, $stage at $now")
                }
            }
        }
        for (stage in listOf(SwapStage.CLAIMED, SwapStage.REFUNDED)) {
            for (reveal in Reveal.entries) assertEquals(Window.Shut, WINDOWS.window(reveal, false, stage, T0, T1, T0))
        }
    }

    @Test
    fun aForwardSwapIsTheMakersToCancelUntilT0TheUsersToClaimUntilT1AndTheUsersForGoodAfterTheLateWindow() {
        fun claim(
            stage: SwapStage,
            now: Long
        ) = WINDOWS.window(Reveal.CLAIM, false, stage, T0, T1, now)

        fun refund(
            stage: SwapStage,
            now: Long
        ) = WINDOWS.window(Reveal.REFUND, false, stage, T0, T1, now)

        assertEquals(Window.Until(T0), refund(SwapStage.OPEN, T0 - 1))
        assertEquals(Window.Shut, claim(SwapStage.OPEN, T0 - 1))
        assertEquals(Window.Until(T1), claim(SwapStage.READY, T0 - 1))
        assertEquals(Window.Until(T1), claim(SwapStage.OPEN, T0))
        assertEquals(Window.Shut, refund(SwapStage.OPEN, T0))
        assertEquals(Window.Until(LATE), refund(SwapStage.READY, T1))
        assertEquals(Window.Shut, claim(SwapStage.READY, LATE - 1))
        assertEquals(Window.Lasting, claim(SwapStage.OPEN, LATE))
        assertEquals(Window.Shut, refund(SwapStage.OPEN, LATE))
    }

    @Test
    fun aReverseEscrowIsTheMakersToClaimOnceReadyUntilT1AndTheUsersToRefundOtherwise() {
        fun claim(
            stage: SwapStage,
            now: Long
        ) = WINDOWS.window(Reveal.CLAIM, true, stage, T0, T1, now)

        fun refund(
            stage: SwapStage,
            now: Long
        ) = WINDOWS.window(Reveal.REFUND, true, stage, T0, T1, now)

        assertEquals(Window.Lasting, refund(SwapStage.OPEN, T0 - 1))
        assertEquals(Window.Lasting, refund(SwapStage.OPEN, LATE))
        assertEquals(Window.Shut, claim(SwapStage.OPEN, T0))
        assertEquals(Window.Until(T1), claim(SwapStage.READY, T1 - 1))
        assertEquals(Window.Shut, refund(SwapStage.READY, T1 - 1))
        assertEquals(Window.Lasting, refund(SwapStage.READY, T1))
        assertEquals(Window.Shut, claim(SwapStage.READY, T1))
    }

    @Test
    fun aClaimNeedsItsMarginByTheLaterClockAndBeforeT0AReadyDeepEnough() {
        val head = ChainHead(1_000, T0 - 600)
        val deep = Go.OnceReadyAt(1_000 - READY_DEPTH)
        assertEquals(deep, WINDOWS.forwardClaim(SwapStage.READY, T0, T1, head, 0, MARGIN))
        assertEquals(Refusal.Shut, WINDOWS.forwardClaim(SwapStage.OPEN, T0, T1, head, 0, MARGIN))
        val atT0 = ChainHead(1_000, T0)
        assertEquals(Go.Now, WINDOWS.forwardClaim(SwapStage.OPEN, T0, T1, atT0, 0, MARGIN))
        // The device's clock ahead can only make a claim go earlier, never later.
        assertEquals(Refusal.TooSoon, WINDOWS.forwardClaim(SwapStage.OPEN, T0, T1, atT0, T1 - MARGIN, MARGIN))
        assertEquals(Go.Now, WINDOWS.forwardClaim(SwapStage.OPEN, T0, T1, atT0, T1 - MARGIN - 1, MARGIN))
        val late = ChainHead(1_000, T1)
        assertEquals(Refusal.MakersUntil(LATE), WINDOWS.forwardClaim(SwapStage.READY, T0, T1, late, 0, MARGIN))
        assertEquals(Go.Now, WINDOWS.forwardClaim(SwapStage.OPEN, T0, T1, late.copy(time = LATE), 0, MARGIN))
        assertEquals(Refusal.Shut, WINDOWS.forwardClaim(SwapStage.CLAIMED, T0, T1, late, 0, MARGIN))
        val early = ChainHead(1, T0 - 600)
        assertEquals(Go.OnceReadyAt(0), WINDOWS.forwardClaim(SwapStage.READY, T0, T1, early, 0, MARGIN))
    }

    @Test
    fun anOpenEscrowIsNeverRefundedWhileAReadyWeSignedCanStillLand() {
        val deadline = T0 - 1_000
        val open = { now: Long -> WINDOWS.reverseRefund(SwapStage.OPEN, T0, T1, ChainHead(1, now), deadline, false) }

        assertEquals(Refusal.ReadyMayLand, open(deadline - 1))
        assertEquals(Refusal.ReadyMayLand, open(deadline))
        assertNull(open(deadline + 1))
        assertNull(WINDOWS.reverseRefund(SwapStage.OPEN, T0, T1, ChainHead(1, T0 - 1), null, false))
        // A ready the sender itself sent, and that may land, holds the refund too.
        assertEquals(Refusal.ReadyMayLand, WINDOWS.reverseRefund(SwapStage.OPEN, T0, T1, ChainHead(1, T1), null, true))
        // A ready escrow is the maker's to claim until t1.
        assertEquals(Refusal.Shut, WINDOWS.reverseRefund(SwapStage.READY, T0, T1, ChainHead(1, T1 - 1), null, false))
        assertNull(WINDOWS.reverseRefund(SwapStage.READY, T0, T1, ChainHead(1, T1), deadline, false))
    }

    @Test
    fun aReadyIsSignedOnlyOnAnOpenEscrowWithItsLifeBeforeT0() {
        assertNull(reverseReady(SwapStage.OPEN, T0, ChainHead(1, T0 - TTL - 1), TTL))
        assertEquals(Refusal.TooSoon, reverseReady(SwapStage.OPEN, T0, ChainHead(1, T0 - TTL), TTL))
        assertEquals(Refusal.Shut, reverseReady(SwapStage.READY, T0, ChainHead(1, 0), TTL))
        val endOfTime = ChainHead(1, Long.MAX_VALUE - 1)
        assertEquals(Refusal.TooSoon, reverseReady(SwapStage.OPEN, Long.MAX_VALUE, endOfTime, TTL))
    }

    @Test
    fun deadlinesNearTheEndOfTimeNeverWrapIntoAnOpenWindow() {
        assertEquals(Long.MAX_VALUE, (Long.MAX_VALUE - 5) plusAtMost 10)
        assertFalse(Window.Until(Long.MAX_VALUE).leaves(Long.MAX_VALUE - 1, MARGIN))
        val forever = SwapWindows(min = 1, late = Long.MAX_VALUE)
        assertEquals(Window.Shut, forever.window(Reveal.CLAIM, false, SwapStage.OPEN, T0, T1, T1 + 1))
        assertFailsWith<IllegalArgumentException> { SwapWindows(min = 0, late = 1) }
    }

    private companion object {
        val WINDOWS = SwapWindows(min = 1_800, late = 3_600)
        const val T0 = 1_792_010_000L
        const val T1 = T0 + 3_600
        const val LATE = T1 + 3_600
        const val MARGIN = CLAIM_MARGIN_SECONDS
        const val TTL = SIGNATURE_TTL_SECONDS
    }
}
