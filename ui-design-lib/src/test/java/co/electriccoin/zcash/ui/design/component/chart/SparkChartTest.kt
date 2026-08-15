package co.electriccoin.zcash.ui.design.component.chart

import org.junit.Assert.assertEquals
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
}
