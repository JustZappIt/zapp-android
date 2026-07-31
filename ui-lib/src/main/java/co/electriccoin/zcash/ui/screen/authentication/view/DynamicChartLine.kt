package co.electriccoin.zcash.ui.screen.authentication.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import kotlin.random.Random

@Composable
fun DynamicChartLine(
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT.dp
) {
    val points = remember { generateChartPoints() }

    Canvas(
        modifier =
            modifier
                .height(height)
                .fillMaxWidth()
    ) {
        val path =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                for (i in points.size - 1 downTo 0) {
                    lineTo(size.width * points[i].x, size.height * points[i].y)
                }
                close()
            }
        drawPath(path, color, style = Fill)
    }
}

internal data class ChartPoint(
    val x: Float,
    val y: Float
)

internal fun generateChartPoints(): List<ChartPoint> {
    val controlPoints = Random.nextInt(MIN_CONTROL_POINTS, MAX_CONTROL_POINTS + 1)
    val totalPoints = controlPoints + EDGE_POINTS
    val xStep = 1f / (totalPoints - 1)
    val heightsFromRight = mutableListOf<Float>()
    var previousHeight = 0f

    repeat(totalPoints) { indexFromRight ->
        heightsFromRight.add(previousHeight)
        if (indexFromRight % 2 == 0 && indexFromRight < totalPoints - 1) {
            previousHeight += randomInRange(MIN_HEIGHT_STEP, MAX_HEIGHT_STEP)
        }
    }

    val heights = heightsFromRight.reversed()
    val maxHeight = heights.maxOrNull()?.takeIf { it > 0f } ?: 1f

    return heights.mapIndexed { index, height ->
        val xJitter =
            if (index in 1 until totalPoints - 1) {
                randomInRange(-X_JITTER_FACTOR, X_JITTER_FACTOR)
            } else {
                0f
            }

        ChartPoint(
            x = ((index * xStep) + xJitter).coerceIn(MIN_X, MAX_X),
            y = (MIN_VISIBLE_Y + ((height / maxHeight) * (MAX_VISIBLE_Y - MIN_VISIBLE_Y))).coerceIn(MIN_Y, MAX_Y)
        )
    }
}

private fun randomInRange(min: Float, max: Float): Float = min + (Random.nextFloat() * (max - min))

private const val MIN_CONTROL_POINTS = 4
private const val MAX_CONTROL_POINTS = 7
private const val EDGE_POINTS = 2
private const val MIN_HEIGHT_STEP = 0.18f
private const val MAX_HEIGHT_STEP = 0.34f
private const val X_JITTER_FACTOR = 0.05f
private const val MIN_VISIBLE_Y = 0.15f
private const val MAX_VISIBLE_Y = 0.95f
private const val MIN_X = 0f
private const val MAX_X = 1f
private const val MIN_Y = 0f
private const val MAX_Y = 1f
internal const val CHART_HEIGHT = 114

@PreviewScreens
@Composable
private fun DynamicChartLinePreview() =
    ZcashTheme {
        BlankSurface {
            DynamicChartLine(
                color = ZcashTheme.colors.welcomeAnimationColor
            )
        }
    }
