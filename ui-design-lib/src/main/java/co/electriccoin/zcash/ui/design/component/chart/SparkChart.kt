package co.electriccoin.zcash.ui.design.component.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme

/**
 * Single-series chart data. [points] are plotted in order; the x-axis is purely positional.
 * Provide at least two points to render a line. Fewer points collapse to a flat line.
 */
data class SparkChartData(
    val points: List<Point>
) {
    data class Point(
        val x: Double,
        val y: Double
    )

    val isRenderable: Boolean get() = points.size >= 2
}

/**
 * Reusable area chart: a stroked line over a vertical gradient fill. No axes, no ticks — it's
 * a visual summary meant to sit inside a card alongside a numeric label. Callers compute
 * [SparkChartData] in whatever units make sense; the chart auto-scales both axes to fit.
 */
@Composable
fun SparkChart(
    data: SparkChartData,
    modifier: Modifier = Modifier,
    lineColor: Color = ZappTheme.colors.accent,
    fillColor: Color = lineColor,
    height: Dp = 140.dp,
    strokeWidth: Dp = 2.dp,
) {
    if (!data.isRenderable) return

    val strokeBrush =
        remember(lineColor) {
            androidx.compose.ui.graphics
                .SolidColor(lineColor)
        }
    val fillBrush =
        remember(fillColor) {
            Brush.verticalGradient(
                0f to fillColor.copy(alpha = 0.24f),
                1f to Color.Transparent,
            )
        }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
    ) {
        val xs = data.points.map { it.x }
        val ys = data.points.map { it.y }
        val xMin = xs.min()
        val xMax = xs.max()
        val yMin = ys.min()
        val yMax = ys.max()
        val xRange = (xMax - xMin).takeIf { it > 0.0 } ?: 1.0
        val yRange = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0

        val topPadding = strokeWidth.toPx()
        val bottomPadding = strokeWidth.toPx()
        val availableHeight = size.height - topPadding - bottomPadding

        val offsets =
            data.points.map { point ->
                Offset(
                    x = ((point.x - xMin) / xRange * size.width).toFloat(),
                    y = topPadding + ((yMax - point.y) / yRange * availableHeight).toFloat(),
                )
            }

        val linePath =
            Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                for (i in 1 until offsets.size) {
                    lineTo(offsets[i].x, offsets[i].y)
                }
            }
        val fillPath =
            Path().apply {
                addPath(linePath)
                lineTo(offsets.last().x, size.height)
                lineTo(offsets.first().x, size.height)
                close()
            }

        drawPath(path = fillPath, brush = fillBrush)
        drawPath(
            path = linePath,
            brush = strokeBrush,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SparkChartPreview() =
    ZcashTheme {
        ProvideZappTheme {
            SparkChart(
                data =
                    SparkChartData(
                        points =
                            listOf(
                                SparkChartData.Point(0.0, 10.0),
                                SparkChartData.Point(1.0, 14.0),
                                SparkChartData.Point(2.0, 12.0),
                                SparkChartData.Point(3.0, 22.0),
                                SparkChartData.Point(4.0, 34.0),
                                SparkChartData.Point(5.0, 12.0),
                                SparkChartData.Point(6.0, 18.0),
                            )
                    ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
