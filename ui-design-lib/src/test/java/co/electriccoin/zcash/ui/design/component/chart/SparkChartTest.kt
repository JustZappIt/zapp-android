package co.electriccoin.zcash.ui.design.component.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkChartTest {
    private val points =
        listOf(
            SparkChartData.Point(x = 0.0, y = 1.0),
            SparkChartData.Point(x = 10.0, y = 2.0),
            SparkChartData.Point(x = 70.0, y = 3.0),
            SparkChartData.Point(x = 100.0, y = 4.0),
        )

    @Test
    fun nearestPointIndex_finds_nearest_irregular_point() {
        assertEquals(1, points.nearestPointIndex(x = 20f, width = 100f))
        assertEquals(2, points.nearestPointIndex(x = 60f, width = 100f))
    }

    @Test
    fun nearestPointIndex_clamps_to_chart_edges() {
        assertEquals(0, points.nearestPointIndex(x = -10f, width = 100f))
        assertEquals(3, points.nearestPointIndex(x = 110f, width = 100f))
    }

    @Test
    fun nearestPointIndex_chooses_closest_point_around_midpoint() {
        assertEquals(0, points.nearestPointIndex(x = 4f, width = 100f))
        assertEquals(1, points.nearestPointIndex(x = 6f, width = 100f))
    }

    @Test
    fun crosshairGuides_run_from_the_point_to_the_left_and_bottom_axes() {
        val selected = Offset(30f, 40f)

        val guides = crosshairGuides(selectedOffset = selected, size = Size(100f, 80f))

        assertEquals(selected, guides.verticalStart)
        assertEquals(Offset(30f, 80f), guides.verticalEnd)
        assertEquals(Offset(0f, 40f), guides.horizontalStart)
        assertEquals(selected, guides.horizontalEnd)
    }

    @Test
    fun crosshairGuides_draw_nothing_above_or_right_of_the_point() {
        val selected = Offset(30f, 40f)

        val guides = crosshairGuides(selectedOffset = selected, size = Size(100f, 80f))

        // The vertical guide never rises above the point, and the horizontal guide never runs past
        // it — those halves labelled nothing and only crowded the marker.
        assertTrue(guides.verticalStart.y >= selected.y && guides.verticalEnd.y >= selected.y)
        assertTrue(guides.horizontalStart.x <= selected.x && guides.horizontalEnd.x <= selected.x)
    }

    @Test
    fun crosshairGuides_stay_within_bounds_at_the_chart_edges() {
        val size = Size(100f, 80f)

        val topLeft = crosshairGuides(selectedOffset = Offset(0f, 0f), size = size)
        assertEquals(Offset(0f, 0f), topLeft.verticalStart)
        assertEquals(Offset(0f, 80f), topLeft.verticalEnd)
        assertEquals(Offset(0f, 0f), topLeft.horizontalStart)

        val bottomRight = crosshairGuides(selectedOffset = Offset(100f, 80f), size = size)
        assertEquals(Offset(100f, 80f), bottomRight.verticalStart)
        assertEquals(Offset(100f, 80f), bottomRight.verticalEnd)
        assertEquals(Offset(0f, 80f), bottomRight.horizontalStart)
    }
}
