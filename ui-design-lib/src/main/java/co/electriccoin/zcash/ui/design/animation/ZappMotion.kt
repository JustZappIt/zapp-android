package co.electriccoin.zcash.ui.design.animation

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * Shared motion vocabulary for Zapp micro-interactions. Swiss design language:
 * short, crisp tweens — no springs, no overshoot.
 */
object ZappMotion {
    /** Small state changes: color swaps, press feedback, dot fills. */
    const val STATE_MS = 120

    /** Content swaps: tab crossfade, error text reveal, step transitions. */
    const val CONTENT_MS = 200

    /** Ceremonial reveals: seed unblur, success moments. */
    const val REVEAL_MS = 350

    /** Full rejection-shake cycle. */
    const val SHAKE_MS = 400

    val easing: Easing = FastOutSlowInEasing
}
