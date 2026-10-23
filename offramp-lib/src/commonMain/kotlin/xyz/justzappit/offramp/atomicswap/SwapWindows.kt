// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable

/**
 * The windows contract's two window lengths in seconds, fixed when it was deployed. At every moment at most one side
 * of a swap may reveal its share, by the clock and the swap's stage alone, and a reveal mined outside its window
 * reverts with the share in public calldata.
 */
@Serializable
data class SwapWindows(
    /** `MIN_WINDOW()`: the least an open leaves before `t0`, and from `t0` to `t1`. */
    val min: Long,
    /** `LATE_WINDOW()`: how long from `t1` only the maker may refund a forward swap; after it, its user may claim. */
    val late: Long,
) {
    init {
        require(min > 0 && late > 0) { "a window lasts" }
    }

    /**
     * The window, at chain time [now], of [reveal] on a swap at [stage] with deadlines [t0] and [t1], a reverse escrow
     * where [reverse]: as the contract's `_claimable` and `_refundable` judge it (zecSwap's `Windows::window`). At most
     * one of a swap's two reveals is ever open.
     */
    fun window(
        reveal: Reveal,
        reverse: Boolean,
        stage: SwapStage,
        t0: Long,
        t1: Long,
        now: Long,
    ): Window =
        when {
            stage != SwapStage.OPEN && stage != SwapStage.READY -> Window.Shut
            reveal == Reveal.CLAIM -> claimWindow(reverse, stage, t0, t1, now)
            else -> refundWindow(reverse, stage, t0, t1, now)
        }

    // A ready swap until t1, and a forward one no one made ready from t0 until t1; then, for a forward swap its maker
    // didn't refund in its late window, for good.
    private fun claimWindow(
        reverse: Boolean,
        stage: SwapStage,
        t0: Long,
        t1: Long,
        now: Long,
    ): Window =
        when {
            reverse -> Window.Until(t1).takeIf { now < t1 && stage == SwapStage.READY }
            now >= t1 -> Window.Lasting.takeIf { now >= t1 plusAtMost late }
            else -> Window.Until(t1).takeIf { stage == SwapStage.READY || now >= t0 }
        } ?: Window.Shut

    // Never open while the claim's is: before t1 a swap no one made ready, a forward one only until t0; from t1 a
    // reverse escrow for good, a forward swap for the late window.
    private fun refundWindow(
        reverse: Boolean,
        stage: SwapStage,
        t0: Long,
        t1: Long,
        now: Long,
    ): Window {
        val lateEnds = t1 plusAtMost late
        return when {
            reverse -> Window.Lasting.takeIf { stage == SwapStage.OPEN || now >= t1 }
            now >= t1 -> Window.Until(lateEnds).takeIf { now < lateEnds }
            else -> Window.Until(t0).takeIf { stage == SwapStage.OPEN && now < t0 }
        } ?: Window.Shut
    }
}

/** Which share a reveal makes public: a claim's (the user's forward, the maker's reverse) or a refund's. */
enum class Reveal { CLAIM, REFUND }

/** Whether the contract takes a reveal now, and until when. */
sealed interface Window {
    /** It would revert, its share public. */
    data object Shut : Window

    /** Until [end], exclusive. */
    data class Until(
        val end: Long
    ) : Window

    /** From now on, for good. */
    data object Lasting : Window

    /**
     * Whether a reveal sent at [now] lands in its window with [margin] to spare, where [now] is the later of the
     * chain's time and the device's clock: either can only make a reveal go earlier, and the margin covers a clock
     * behind.
     */
    fun leaves(
        now: Long,
        margin: Long,
    ): Boolean =
        when (this) {
            Shut -> false
            is Until -> (now plusAtMost margin) < end
            Lasting -> true
        }
}

/** A block's number and time: the clock the contract's windows run on. */
data class ChainHead(
    val number: Long,
    val time: Long,
)

/** What a reveal may do now, judged on one read of the chain. */
sealed interface RevealJudgement

/** A reveal may go. */
sealed interface Go : RevealJudgement {
    data object Now : Go

    /** Once the swap, read as of [block], is ready there: deep enough below the head for the reveal to rely on it. */
    data class OnceReadyAt(
        val block: Long
    ) : Go
}

/** Why a reveal isn't sent now. */
sealed interface Refusal : RevealJudgement {
    /** Its window is shut: the swap is the other side's now, or no one's. */
    data object Shut : Refusal

    /** From `t1` a forward swap is its maker's to refund; its claim opens again, for good, at [late]. */
    data class MakersUntil(
        val late: Long
    ) : Refusal

    /** Its window ends within the margin: sent now, it might land after and revert, its share public. */
    data object TooSoon : Refusal

    /** A ready of ours may land first, and would revert it with the share public. */
    data object ReadyMayLand : Refusal
}

/**
 * Blocks a ready must have above it before a forward claim is revealed on it ahead of `t0` (zecSwap's `READY_DEPTH`):
 * a claim sent on a ready a reorganisation drops lands reverted, its share public, the swap the maker's to cancel.
 */
const val READY_DEPTH = 2L

/**
 * Blocks a reverse escrow's ready must have above it before its maker's claim is revealed on it (zecSwap's
 * `REVERSE_READY_DEPTH`): its user can't sign the ready again, and a claim on one a reorganisation drops reverts.
 */
const val REVERSE_READY_DEPTH = 4L

/**
 * A claim is revealed only with more than this left in its window (zecSwap's `CLAIM_MARGIN`): one landing after the
 * window closes reverts with its share public, and the swap is the maker's to refund. It covers Flashbots Protect's
 * 10-block range, the relayer's wait, a reorganisation and a lagging node.
 */
const val CLAIM_MARGIN_SECONDS = 5 * 60L

/**
 * How long a signed `ready` or rescue holds (zecSwap's `SIGNATURE_TTL`): a ready is signed only with all of it before
 * `t0`, and a refund waits until the chain is past it.
 */
const val SIGNATURE_TTL_SECONDS = 2 * 60L

/**
 * A forward claim, the user's or a relayer's for it, judged on [head] and the device's clock [wall], with [margin]
 * left in its window (zecSwap's `judge::forward_claim`). Before `t0` it goes only on a ready [READY_DEPTH] blocks deep.
 */
fun SwapWindows.forwardClaim(
    stage: SwapStage,
    t0: Long,
    t1: Long,
    head: ChainHead,
    wall: Long,
    margin: Long,
): RevealJudgement {
    val now = head.time
    val window = window(Reveal.CLAIM, reverse = false, stage, t0, t1, now)
    val isLive = stage == SwapStage.OPEN || stage == SwapStage.READY
    return when {
        window == Window.Shut && isLive && now >= t1 -> Refusal.MakersUntil(t1 plusAtMost late)
        window == Window.Shut -> Refusal.Shut
        !window.leaves(maxOf(now, wall), margin) -> Refusal.TooSoon
        now < t0 -> Go.OnceReadyAt((head.number - READY_DEPTH).coerceAtLeast(0))
        else -> Go.Now
    }
}

/**
 * A reverse escrow's claim, its maker's, with [margin] left in its window (zecSwap's `judge::reverse_claim`): only on a
 * ready [REVERSE_READY_DEPTH] blocks deep. The app never makes one; it replays the port spec's vectors.
 */
fun SwapWindows.reverseClaim(
    stage: SwapStage,
    t0: Long,
    t1: Long,
    head: ChainHead,
    wall: Long,
    margin: Long,
): RevealJudgement {
    val window = window(Reveal.CLAIM, reverse = true, stage, t0, t1, head.time)
    return when {
        window == Window.Shut -> Refusal.Shut
        !window.leaves(maxOf(head.time, wall), margin) -> Refusal.TooSoon
        else -> Go.OnceReadyAt((head.number - REVERSE_READY_DEPTH).coerceAtLeast(0))
    }
}

/**
 * Why a reverse escrow's refund, its user's or a relayer's for it, can't go now, or null when it may (zecSwap's
 * `judge::reverse_refund`). [readySignedUntil] is the latest deadline of a ready its user signed, and [readyMayLand]
 * whether one the sender sent may still land: until the chain is past every such deadline, or at `t0`, a ready may land
 * first and revert the refund with the user's share public, the maker's to claim with.
 */
fun SwapWindows.reverseRefund(
    stage: SwapStage,
    t0: Long,
    t1: Long,
    head: ChainHead,
    readySignedUntil: Long?,
    readyMayLand: Boolean,
): Refusal? {
    val now = head.time
    if (window(Reveal.REFUND, reverse = true, stage, t0, t1, now) == Window.Shut) return Refusal.Shut
    val isSigned = stage == SwapStage.OPEN && now < t0 && readySignedUntil != null && now <= readySignedUntil
    return Refusal.ReadyMayLand.takeIf { isSigned || readyMayLand }
}

/**
 * Why a reverse escrow's ready, signed to be good for [ttl] seconds, can't be signed now, or null when it may
 * (zecSwap's `judge::reverse_ready`): only on an escrow not yet ready, and with time to land before `t0`, from which
 * none is taken.
 */
fun reverseReady(
    stage: SwapStage,
    t0: Long,
    head: ChainHead,
    ttl: Long,
): Refusal? =
    when {
        stage != SwapStage.OPEN -> Refusal.Shut
        (head.time plusAtMost ttl) >= t0 -> Refusal.TooSoon
        else -> null
    }

/** `this + other`, held at [Long.MAX_VALUE] rather than wrapping: chain times and windows are never negative. */
internal infix fun Long.plusAtMost(other: Long): Long =
    if (other > 0 && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
