package com.mcqapp

import androidx.compose.ui.unit.dp
import com.mcqapp.ui.stats.charts.ChartGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry helpers behind the stats charts are pure so the mapping that F1-F3
 * hid — how tall a heatmap is, where a tap lands, where a gutter tap points —
 * can be checked on the JVM, without rendering.
 */
class StatsChartGeometryTest {

    @Test
    fun heatmapCellAndHeightUseTheSameWidthAsTheDrawBlock() {
        val cell = ChartGeometry.heatmapCell(100.dp, gap = 2.dp, weeks = 4)
        assertEquals(23.5.dp, cell)
        // 14 dp month strip + 7 rows + 6 gaps.
        assertEquals(190.5.dp, ChartGeometry.heatmapHeight(14.dp, cell, 2.dp))
    }

    @Test
    fun heatmapTapsMapToTheDrawnGridAndNeverGutterToRowZero() {
        val at = ChartGeometry.heatmapCellAt(
            x = 15f,
            y = 25f,
            topOffsetPx = 14f,
            pitchPx = 10f
        )
        assertEquals(Pair(1, 1), at)
        val aboveGrid = ChartGeometry.heatmapCellAt(
            x = 15f,
            y = 4f,
            topOffsetPx = 14f,
            pitchPx = 10f
        )
        assertTrue("month-strip taps must not select row 0", aboveGrid.second < 0)
    }

    @Test
    fun barGutterTapsDoNotTruncateToZero() {
        assertEquals(-1, ChartGeometry.barIndexAt(x = -4.9f, left = 0f, slot = 10f))
        assertEquals(0, ChartGeometry.barIndexAt(x = 1f, left = 0f, slot = 10f))
        assertEquals(1, ChartGeometry.barIndexAt(x = 14.9f, left = 0f, slot = 10f))
    }
}
