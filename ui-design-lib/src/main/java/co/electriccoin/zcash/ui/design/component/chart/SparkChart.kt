package co.electriccoin.zcash.ui.design.component.chart

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import kotlin.math.abs

/**
 * Single-series chart data. [points] must be sorted by ascending x-value and are plotted in order.
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

/** Axis readouts shown for the nearest point while a user touches or scrubs the chart. */
data class SparkChartSelection(
    val primary: String,
    val secondary: String,
    val contentDescription: String,
)

/**
 * Reusable area chart: a stroked line over a vertical gradient fill. No axes, no ticks — it's
 * a visual summary meant to sit inside a card alongside a numeric label. Callers compute
 * [SparkChartData] in whatever units make sense; the chart auto-scales both axes to fit. Supplying
 * [selectionFormatter] enables tap-and-drag inspection with crosshair guides and point haptics.
 */
@Composable
fun SparkChart(
    data: SparkChartData,
    modifier: Modifier = Modifier,
    lineColor: Color = ZappTheme.colors.accent,
    fillColor: Color = lineColor,
    height: Dp = 140.dp,
    strokeWidth: Dp = 2.dp,
    selectionFormatter: ((SparkChartData.Point) -> SparkChartSelection)? = null,
) {
    if (!data.isRenderable) return

    val selectedIndex = remember(data.points) { mutableStateOf<Int?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val colors = ZappTheme.colors
    val primaryTextStyle = ZappTheme.typography.mono.copy(color = lineColor)
    val secondaryTextStyle = ZappTheme.typography.groupLabel.copy(color = colors.textMuted)
    val strokeBrush = remember(lineColor) { SolidColor(lineColor) }
    val fillBrush =
        remember(fillColor) {
            Brush.verticalGradient(
                0f to fillColor.copy(alpha = 0.24f),
                1f to Color.Transparent,
            )
        }
    val selectionCache =
        remember(data.points, selectionFormatter, textMeasurer, primaryTextStyle, secondaryTextStyle) {
            SelectionCache(
                points = data.points,
                formatter = selectionFormatter,
                textMeasurer = textMeasurer,
                primaryTextStyle = primaryTextStyle,
                secondaryTextStyle = secondaryTextStyle,
            )
        }
    val interactionModifier =
        Modifier.chartScrubbing(
            data = data,
            enabled = selectionFormatter != null,
            selectedIndex = selectedIndex,
        )

    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .then(interactionModifier)
                .semantics {
                    selectedIndex.value
                        ?.let(selectionCache::get)
                        ?.selection
                        ?.contentDescription
                        ?.let { stateDescription = it }
                }.drawWithCache {
                    val strokeWidthPx = strokeWidth.toPx()
                    val geometry = chartGeometry(data = data, strokeWidthPx = strokeWidthPx, size = size)
                    val lineStroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                    val guideEffect =
                        PathEffect.dashPathEffect(
                            intervals = floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                        )
                    onDrawBehind {
                        drawSparkChart(
                            geometry = geometry,
                            strokeBrush = strokeBrush,
                            fillBrush = fillBrush,
                            lineStroke = lineStroke,
                            lineColor = lineColor,
                            markerBackground = colors.bg,
                            guideEffect = guideEffect,
                            selectedIndex = selectedIndex.value,
                            selectionCache = selectionCache,
                        )
                    }
                }
    )
}

@Composable
private fun Modifier.chartScrubbing(
    data: SparkChartData,
    enabled: Boolean,
    selectedIndex: MutableState<Int?>,
): Modifier {
    if (!enabled) return this
    val haptic = LocalHapticFeedback.current
    return pointerInput(data.points) {
        fun selectNearest(x: Float) {
            val next = data.points.nearestPointIndex(x = x, width = size.width.toFloat())
            if (next != selectedIndex.value) {
                selectedIndex.value = next
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            selectNearest(down.position.x)
            var horizontalDrag = false
            var pointerPressed = true

            while (pointerPressed) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                pointerPressed = change?.pressed == true
                if (change != null && change.pressed) {
                    val totalDrag = change.position - down.position
                    val passedHorizontalSlop =
                        abs(totalDrag.x) > viewConfiguration.touchSlop &&
                            abs(totalDrag.x) > abs(totalDrag.y)
                    if (!horizontalDrag && passedHorizontalSlop) horizontalDrag = true
                    if (horizontalDrag) change.consume()
                    selectNearest(change.position.x)
                }
            }
        }
    }
}

private data class ChartGeometry(
    val offsets: List<Offset>,
    val linePath: Path,
    val fillPath: Path,
)

private data class RenderedSelection(
    val selection: SparkChartSelection,
    val primaryText: TextLayoutResult,
    val secondaryText: TextLayoutResult,
)

private class SelectionCache(
    private val points: List<SparkChartData.Point>,
    private val formatter: ((SparkChartData.Point) -> SparkChartSelection)?,
    private val textMeasurer: TextMeasurer,
    private val primaryTextStyle: TextStyle,
    private val secondaryTextStyle: TextStyle,
) {
    private var renderedIndex: Int? = null
    private var renderedSelection: RenderedSelection? = null

    fun get(index: Int): RenderedSelection? {
        val point = points.getOrNull(index)
        return if (point == null || formatter == null) {
            null
        } else if (renderedIndex == index) {
            renderedSelection
        } else {
            formatter
                .invoke(point)
                .let { selection ->
                    RenderedSelection(
                        selection = selection,
                        primaryText = textMeasurer.measure(AnnotatedString(selection.primary), primaryTextStyle),
                        secondaryText =
                            textMeasurer.measure(
                                AnnotatedString(selection.secondary),
                                secondaryTextStyle,
                            ),
                    )
                }.also {
                    renderedIndex = index
                    renderedSelection = it
                }
        }
    }
}

private fun DrawScope.drawSparkChart(
    geometry: ChartGeometry,
    strokeBrush: Brush,
    fillBrush: Brush,
    lineStroke: Stroke,
    lineColor: Color,
    markerBackground: Color,
    guideEffect: PathEffect,
    selectedIndex: Int?,
    selectionCache: SelectionCache,
) {
    drawPath(path = geometry.fillPath, brush = fillBrush)
    drawPath(
        path = geometry.linePath,
        brush = strokeBrush,
        style = lineStroke,
    )
    val selectedOffset = selectedIndex?.let(geometry.offsets::getOrNull) ?: return
    drawCrosshair(
        selectedOffset = selectedOffset,
        lineColor = lineColor,
        markerBackground = markerBackground,
        guideEffect = guideEffect,
    )
    selectionCache.get(selectedIndex)?.let {
        drawAxisReadouts(
            selectedOffset = selectedOffset,
            selection = it,
        )
    }
}

private fun chartGeometry(
    data: SparkChartData,
    strokeWidthPx: Float,
    size: Size,
): ChartGeometry {
    val xMin = data.points.first().x
    val xRange = (data.points.last().x - xMin).takeIf { it > 0.0 } ?: 1.0
    var yMin = data.points.first().y
    var yMax = yMin
    for (index in 1 until data.points.size) {
        yMin = minOf(yMin, data.points[index].y)
        yMax = maxOf(yMax, data.points[index].y)
    }
    val yRange = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0
    val topPadding = strokeWidthPx
    val availableHeight = size.height - topPadding * 2f
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
            for (index in 1 until offsets.size) {
                lineTo(offsets[index].x, offsets[index].y)
            }
        }
    val fillPath =
        Path().apply {
            addPath(linePath)
            lineTo(offsets.last().x, size.height)
            lineTo(offsets.first().x, size.height)
            close()
        }
    return ChartGeometry(offsets = offsets, linePath = linePath, fillPath = fillPath)
}

private fun DrawScope.drawCrosshair(
    selectedOffset: Offset,
    lineColor: Color,
    markerBackground: Color,
    guideEffect: PathEffect,
) {
    val guides = crosshairGuides(selectedOffset = selectedOffset, size = size)
    drawLine(
        color = lineColor.copy(alpha = 0.5f),
        start = guides.verticalStart,
        end = guides.verticalEnd,
        strokeWidth = 1.dp.toPx(),
        pathEffect = guideEffect,
    )
    drawLine(
        color = lineColor.copy(alpha = 0.5f),
        start = guides.horizontalStart,
        end = guides.horizontalEnd,
        strokeWidth = 1.dp.toPx(),
        pathEffect = guideEffect,
    )
    val markerOuter = 10.dp.toPx()
    val markerInner = 6.dp.toPx()
    drawRect(
        color = markerBackground,
        topLeft = selectedOffset - Offset(markerOuter / 2f, markerOuter / 2f),
        size = Size(markerOuter, markerOuter),
    )
    drawRect(
        color = lineColor,
        topLeft = selectedOffset - Offset(markerInner / 2f, markerInner / 2f),
        size = Size(markerInner, markerInner),
    )
}

/**
 * Endpoints of the two selection guides. Each guide runs from the selected point toward the axis
 * that labels it — left to the price readout, down to the date readout. The segments above and to
 * the right of the point are deliberately omitted: they label nothing and box the marker in.
 */
internal data class CrosshairGuides(
    val verticalStart: Offset,
    val verticalEnd: Offset,
    val horizontalStart: Offset,
    val horizontalEnd: Offset,
)

internal fun crosshairGuides(
    selectedOffset: Offset,
    size: Size,
): CrosshairGuides =
    CrosshairGuides(
        verticalStart = selectedOffset,
        verticalEnd = Offset(selectedOffset.x, size.height),
        horizontalStart = Offset(0f, selectedOffset.y),
        horizontalEnd = selectedOffset,
    )

private fun DrawScope.drawAxisReadouts(
    selectedOffset: Offset,
    selection: RenderedSelection,
) {
    val primary = selection.primaryText
    val secondary = selection.secondaryText
    val labelInset = 4.dp.toPx()
    val axisGap = 4.dp.toPx()
    val priceY =
        (selectedOffset.y - primary.size.height - axisGap)
            .coerceIn(0f, (size.height - primary.size.height).coerceAtLeast(0f))
    val dateX =
        if (selectedOffset.x + axisGap + secondary.size.width + labelInset <= size.width) {
            selectedOffset.x + axisGap
        } else {
            selectedOffset.x - axisGap - secondary.size.width
        }.coerceIn(labelInset, (size.width - secondary.size.width - labelInset).coerceAtLeast(labelInset))
    val dateY = size.height - secondary.size.height - labelInset

    drawText(
        textLayoutResult = primary,
        topLeft = Offset(labelInset, priceY),
    )
    drawText(
        textLayoutResult = secondary,
        topLeft = Offset(dateX, dateY),
    )
}

internal fun List<SparkChartData.Point>.nearestPointIndex(
    x: Float,
    width: Float,
): Int =
    if (isEmpty()) {
        0
    } else {
        val xMin = first().x
        val xRange = last().x - xMin
        if (xRange <= 0.0) {
            0
        } else {
            val targetX = xMin + x.coerceIn(0f, width) / width.coerceAtLeast(1f) * xRange
            findNearestSortedIndex(targetX)
        }
    }

private fun List<SparkChartData.Point>.findNearestSortedIndex(targetX: Double): Int {
    var low = 0
    var high = lastIndex
    var match: Int? = null
    while (low <= high && match == null) {
        val middle = (low + high).ushr(1)
        when {
            this[middle].x < targetX -> low = middle + 1
            this[middle].x > targetX -> high = middle - 1
            else -> match = middle
        }
    }
    return match
        ?: when {
            low <= 0 -> {
                0
            }

            low > lastIndex -> {
                lastIndex
            }

            else -> {
                val before = low - 1
                if (abs(this[before].x - targetX) <= abs(this[low].x - targetX)) before else low
            }
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
