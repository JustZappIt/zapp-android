package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import kotlinx.coroutines.launch

/**
 * A checkmark stroked on via path-trim (progress 0..1) inside a `side`-square at [topLeft]. Square
 * caps and a miter joint keep it Swiss-crisp.
 */
fun DrawScope.drawTrimmedCheck(
    topLeft: Offset,
    side: Float,
    progress: Float,
    color: Color,
    strokeWidth: Float,
) {
    if (progress <= 0f) return
    val path =
        Path().apply {
            moveTo(topLeft.x + side * CHECK_P0_X, topLeft.y + side * CHECK_P0_Y)
            lineTo(topLeft.x + side * CHECK_P1_X, topLeft.y + side * CHECK_P1_Y)
            lineTo(topLeft.x + side * CHECK_P2_X, topLeft.y + side * CHECK_P2_Y)
        }
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    measure.getSegment(0f, measure.length * progress, segment, true)
    drawPath(
        path = segment,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Square, join = StrokeJoin.Miter),
    )
}

/**
 * The terminal-success moment for every money flow: a sharp accent square stamps in, the checkmark
 * strokes on, and a single square outline rings outward once and fades. Crisp tweens only — no
 * springs, no overshoot.
 */
@Composable
fun ZappSuccessBadge(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    val accent = c.accent
    val onAccent = c.onAccent
    val badgeIn = remember { Animatable(0f) }
    val checkTrim = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        badgeIn.animateTo(1f, tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing))
        launch { pulse.animateTo(1f, tween(COMPLETE_PULSE_MS, easing = ZappMotion.easing)) }
        checkTrim.animateTo(1f, tween(ZappMotion.REVEAL_MS, easing = ZappMotion.easing))
    }
    Canvas(modifier = modifier.size(COMPLETE_CANVAS_SIZE.dp)) {
        val badge = COMPLETE_MARK_SIZE.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f

        if (pulse.value > 0f) {
            val side = badge * (1f + COMPLETE_PULSE_GROW * pulse.value)
            drawRect(
                color = accent,
                topLeft = Offset(cx - side / 2f, cy - side / 2f),
                size = Size(side, side),
                alpha = (1f - pulse.value) * COMPLETE_PULSE_MAX_ALPHA,
                style = Stroke(width = COMPLETE_PULSE_STROKE.dp.toPx()),
            )
        }

        val scaled = badge * (COMPLETE_MARK_INITIAL_SCALE + (1f - COMPLETE_MARK_INITIAL_SCALE) * badgeIn.value)
        drawRect(
            color = accent,
            topLeft = Offset(cx - scaled / 2f, cy - scaled / 2f),
            size = Size(scaled, scaled),
            alpha = badgeIn.value,
        )

        drawTrimmedCheck(
            topLeft = Offset(cx - badge / 2f, cy - badge / 2f),
            side = badge,
            progress = checkTrim.value,
            color = onAccent,
            strokeWidth = badge * COMPLETE_CHECK_STROKE_FRAC,
        )
    }
}

private const val COMPLETE_CANVAS_SIZE = 120
private const val COMPLETE_MARK_SIZE = 64
private const val COMPLETE_MARK_INITIAL_SCALE = 0.86f
private const val COMPLETE_CHECK_STROKE_FRAC = 0.08f
private const val COMPLETE_PULSE_GROW = 0.75f
private const val COMPLETE_PULSE_STROKE = 1.5f
private const val COMPLETE_PULSE_MAX_ALPHA = 0.45f
private const val COMPLETE_PULSE_MS = 520
private const val CHECK_P0_X = 0.26f
private const val CHECK_P0_Y = 0.50f
private const val CHECK_P1_X = 0.43f
private const val CHECK_P1_Y = 0.67f
private const val CHECK_P2_X = 0.75f
private const val CHECK_P2_Y = 0.34f
