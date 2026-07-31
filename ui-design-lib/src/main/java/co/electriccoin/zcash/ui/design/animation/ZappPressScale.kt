package co.electriccoin.zcash.ui.design.animation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Tactile press compression: scales the element to [pressedScale] while the
 * [interactionSource] reports a press. Layer on top of the existing ripple —
 * it complements, not replaces, the indication.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = tween(durationMillis = ZappMotion.STATE_MS, easing = ZappMotion.easing),
            label = "pressScale",
        )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
