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
 * A gently curved check stroked on via path-trim. Cubic segments and rounded caps keep both the
 * large success mark and the small Done-button mark smooth at every point in their reveal.
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
            cubicTo(
                topLeft.x + side * CHECK_P0_CONTROL_1_X,
                topLeft.y + side * CHECK_P0_CONTROL_1_Y,
                topLeft.x + side * CHECK_P0_CONTROL_2_X,
                topLeft.y + side * CHECK_P0_CONTROL_2_Y,
                topLeft.x + side * CHECK_P1_X,
                topLeft.y + side * CHECK_P1_Y,
            )
            cubicTo(
                topLeft.x + side * CHECK_P1_CONTROL_1_X,
                topLeft.y + side * CHECK_P1_CONTROL_1_Y,
                topLeft.x + side * CHECK_P1_CONTROL_2_X,
                topLeft.y + side * CHECK_P1_CONTROL_2_Y,
                topLeft.x + side * CHECK_P2_X,
                topLeft.y + side * CHECK_P2_Y,
            )
        }
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    measure.getSegment(0f, measure.length * progress, segment, true)
    drawPath(
        path = segment,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * The terminal-success moment for every money flow. A clean accent medallion settles once while a
 * complete outer ring resolves around it and the curved check draws on. Closing the ring makes the
 * final frame read as fully finished without adding translucent echoes, irregular blobs or gloss.
 *
 * Drawn in the accent rather than the completion ramp: the badge is the brand's moment, while the
 * ramp stays with [ZappDoneButton], which needs a fill that differs from the primary button it
 * replaces at exactly that instant.
 */
@Composable
fun ZappSuccessBadge(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    val markIn = remember { Animatable(0f) }
    val checkTrim = remember { Animatable(0f) }
    val orbit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { orbit.animateTo(1f, tween(COMPLETE_ORBIT_MS, easing = ZappMotion.easing)) }
        markIn.animateTo(1f, tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing))
        checkTrim.animateTo(1f, tween(ZappMotion.REVEAL_MS, easing = ZappMotion.easing))
    }
    Canvas(modifier = modifier.size(COMPLETE_CANVAS_SIZE.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val mark = COMPLETE_MARK_SIZE.dp.toPx()
        val markScale = COMPLETE_MARK_INITIAL_SCALE + (1f - COMPLETE_MARK_INITIAL_SCALE) * markIn.value
        val radius = mark / 2f * markScale
        val orbitSide = COMPLETE_ORBIT_SIZE.dp.toPx()
        val orbitTopLeft = Offset(cx - orbitSide / 2f, cy - orbitSide / 2f)

        drawArc(
            color = c.accentShade,
            startAngle = COMPLETE_ORBIT_START_DEGREES,
            sweepAngle = COMPLETE_ORBIT_SWEEP_DEGREES * orbit.value,
            useCenter = false,
            topLeft = orbitTopLeft,
            size = Size(orbitSide, orbitSide),
            style = Stroke(width = COMPLETE_ORBIT_STROKE.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = c.shadow,
            radius = radius,
            center = Offset(cx, cy + COMPLETE_SHADOW_OFFSET.dp.toPx()),
            alpha = COMPLETE_SHADOW_ALPHA * markIn.value,
        )
        drawCircle(
            color = c.accentShade,
            radius = radius,
            center = Offset(cx, cy + COMPLETE_DEPTH_OFFSET.dp.toPx()),
            alpha = markIn.value,
        )
        drawCircle(
            color = c.accent,
            radius = radius,
            center = Offset(cx, cy),
            alpha = markIn.value,
        )

        drawTrimmedCheck(
            topLeft = Offset(cx - COMPLETE_CHECK_SIZE.dp.toPx() / 2f, cy - COMPLETE_CHECK_SIZE.dp.toPx() / 2f),
            side = COMPLETE_CHECK_SIZE.dp.toPx(),
            progress = checkTrim.value,
            color = c.onCompletion,
            strokeWidth = COMPLETE_CHECK_SIZE.dp.toPx() * COMPLETE_CHECK_STROKE_FRAC,
        )
    }
}

private const val COMPLETE_CANVAS_SIZE = 116
private const val COMPLETE_MARK_SIZE = 66
private const val COMPLETE_MARK_INITIAL_SCALE = 0.88f
private const val COMPLETE_ORBIT_SIZE = 88
private const val COMPLETE_ORBIT_STROKE = 2.5f
private const val COMPLETE_ORBIT_START_DEGREES = -72f
private const val COMPLETE_ORBIT_SWEEP_DEGREES = 360f
private const val COMPLETE_ORBIT_MS = 480
private const val COMPLETE_DEPTH_OFFSET = 3
private const val COMPLETE_SHADOW_OFFSET = 5
private const val COMPLETE_SHADOW_ALPHA = 0.12f
private const val COMPLETE_CHECK_SIZE = 56
private const val COMPLETE_CHECK_STROKE_FRAC = 0.085f
private const val CHECK_P0_X = 0.26f
private const val CHECK_P0_Y = 0.51f
private const val CHECK_P0_CONTROL_1_X = 0.30f
private const val CHECK_P0_CONTROL_1_Y = 0.55f
private const val CHECK_P0_CONTROL_2_X = 0.38f
private const val CHECK_P0_CONTROL_2_Y = 0.64f
private const val CHECK_P1_X = 0.43f
private const val CHECK_P1_Y = 0.64f
private const val CHECK_P1_CONTROL_1_X = 0.49f
private const val CHECK_P1_CONTROL_1_Y = 0.64f
private const val CHECK_P1_CONTROL_2_X = 0.68f
private const val CHECK_P1_CONTROL_2_Y = 0.36f
private const val CHECK_P2_X = 0.77f
private const val CHECK_P2_Y = 0.31f
