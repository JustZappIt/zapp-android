package co.electriccoin.zcash.ui.design.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Horizontal rejection shake, run each time [trigger] changes to a value that
 * isn't `null` or `false`. Pass a `Boolean` for one-shot errors or an attempt
 * counter (`Int`) when consecutive failures must each shake.
 *
 * Pair with [androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject] at
 * the call site — the shake is the visual half of the rejection cue.
 */
@Composable
fun Modifier.shake(trigger: Any?): Modifier {
    val offset = remember { Animatable(0f) }
    val distance = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(trigger) {
        if (trigger != null && trigger != false) {
            offset.snapTo(0f)
            offset.animateTo(
                targetValue = 0f,
                animationSpec =
                    keyframes {
                        durationMillis = ZappMotion.SHAKE_MS
                        -distance at SHAKE_STEP_1_MS
                        distance at SHAKE_STEP_2_MS
                        -distance * SHAKE_MID_DISTANCE_FRACTION at SHAKE_STEP_3_MS
                        distance * SHAKE_MID_DISTANCE_FRACTION at SHAKE_STEP_4_MS
                        -distance * SHAKE_SMALL_DISTANCE_FRACTION at SHAKE_STEP_5_MS
                        distance * SHAKE_SMALL_DISTANCE_FRACTION at SHAKE_STEP_6_MS
                        0f at ZappMotion.SHAKE_MS
                    },
            )
        }
    }
    return graphicsLayer { translationX = offset.value }
}

private const val SHAKE_STEP_1_MS = 50
private const val SHAKE_STEP_2_MS = 100
private const val SHAKE_STEP_3_MS = 150
private const val SHAKE_STEP_4_MS = 200
private const val SHAKE_STEP_5_MS = 250
private const val SHAKE_STEP_6_MS = 300
private const val SHAKE_MID_DISTANCE_FRACTION = 0.75f
private const val SHAKE_SMALL_DISTANCE_FRACTION = 0.5f
