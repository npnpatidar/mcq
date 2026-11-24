package com.mcqapp

import androidx.compose.ui.unit.dp
import com.mcqapp.ui.stats.charts.ChartGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun heatmapWeeksAndWidthCoverAll365Days() {
        val weeks = ChartGeometry.heatmapWeeks(365)
        assertEquals(53, weeks)
        // 53 columns of 12 dp cells plus 52 gaps of 2 dp.
        val content = ChartGeometry.heatmapWidth(ChartGeometry.HEATMAP_MIN_CELL, weeks)
        assertEquals(740.dp, content)
        assertTrue("all days must remain reachable by scrolling", content > 320.dp)
        assertTrue(content > 256.dp)
        assertEquals(1, ChartGeometry.heatmapWeeks(1))
        assertEquals(1, ChartGeometry.heatmapWeeks(0))
    }

    @Test
    fun heatmapCellKeepsMinimumOnNarrowViewportsAndFillsWideOnes() {
        val weeks = ChartGeometry.heatmapWeeks(365)
        assertEquals(
            ChartGeometry.HEATMAP_MIN_CELL,
            ChartGeometry.heatmapCell(320.dp, ChartGeometry.HEATMAP_GAP, weeks)
        )
        assertEquals(
            ChartGeometry.HEATMAP_MIN_CELL,
            ChartGeometry.heatmapCell(256.dp, ChartGeometry.HEATMAP_GAP, weeks)
        )
        // 1164 dp is exactly 20 dp * 53 + 2 dp * 52: the cells fill a wide
        // viewport rather than staying pinned at the minimum.
        val wide = 1_164.dp
        assertEquals(20.dp, ChartGeometry.heatmapCell(wide, ChartGeometry.HEATMAP_GAP, weeks))
        assertTrue(ChartGeometry.heatmapCell(wide, ChartGeometry.HEATMAP_GAP, weeks) > ChartGeometry.HEATMAP_MIN_CELL)
        assertEquals(wide, ChartGeometry.heatmapWidth(20.dp, weeks, ChartGeometry.HEATMAP_GAP))
    }

    @Test
    fun scrollableHeatmapKeepsIts110DpHeight() {
        assertEquals(
            110.dp,
            ChartGeometry.heatmapHeight(
                ChartGeometry.HEATMAP_TOP_OFFSET,
                ChartGeometry.HEATMAP_MIN_CELL,
                ChartGeometry.HEATMAP_GAP,
                ChartGeometry.HEATMAP_ROWS
            )
        )
    }

    @Test
    fun tapFarIntoTheScrollableContentMapsToTheRightDay() {
        val cellPx = 12f
        val pitch = ChartGeometry.pitch(cellPx, ChartGeometry.HEATMAP_GAP.value)
        val topOffset = ChartGeometry.HEATMAP_TOP_OFFSET.value
        fun indexTappedFor(day: Int): Int? {
            val col = day / ChartGeometry.HEATMAP_ROWS
            val row = day % ChartGeometry.HEATMAP_ROWS
            val x = col * pitch + cellPx / 2f
            val y = topOffset + row * pitch + cellPx / 2f
            val (tapCol, tapRow) = ChartGeometry.heatmapCellAt(x, y, topOffset, pitch)
            return ChartGeometry.heatmapIndex(tapCol, tapRow, ChartGeometry.HEATMAP_ROWS)
        }
        // These taps land far past a 320 dp viewport; the canvas coordinates
        // are content coordinates, so the mapping must still select the day.
        assertTrue("the last week column lies beyond a 320 dp viewport", 52 * pitch > 320f)
        assertEquals(300, indexTappedFor(300))
        assertEquals(364, indexTappedFor(364))
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

    @Test
    fun heatmapLabelStripTapsNeverFoldIntoAnotherColumn() {
        // col*7 + row with row = -1 would land on the previous column's last
        // cell ((c-1)*7 + 6); the guard must reject it instead.
        assertNull(ChartGeometry.heatmapIndex(col = 1, row = -1))
        assertNull(ChartGeometry.heatmapIndex(col = 0, row = -1))
        assertNull(ChartGeometry.heatmapIndex(col = 3, row = 7))
        assertEquals(0, ChartGeometry.heatmapIndex(col = 0, row = 0))
        assertEquals(6, ChartGeometry.heatmapIndex(col = 0, row = 6))
        assertEquals(14, ChartGeometry.heatmapIndex(col = 2, row = 0))
    }
}
