package com.mcqapp.ui.stats.charts

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * Pure geometry for the chart gestures and sizes that have to agree with the
 * draw block. Keeping the arithmetic out of the composables makes the
 * tap-to-cell and canvas-height mappings unit-testable on the JVM.
 */
internal object ChartGeometry {

    const val HEATMAP_ROWS = 7
    val HEATMAP_GAP = 2.dp
    val HEATMAP_TOP_OFFSET = 14.dp
    val HEATMAP_MIN_CELL = 12.dp

    /** Number of week columns needed to show [days] study days. */
    fun heatmapWeeks(days: Int): Int =
        ((days + HEATMAP_ROWS - 1) / HEATMAP_ROWS).coerceAtLeast(1)

    /** Full (unscrolled) width of a [weeks]-column heatmap. */
    fun heatmapWidth(cell: Dp, weeks: Int, gap: Dp = HEATMAP_GAP): Dp {
        val columns = weeks.coerceAtLeast(1)
        return cell * columns + gap * (columns - 1)
    }

    /**
     * Width of one heatmap week column: fill [maxWidth] when there is room, but
     * never shrink below [minCell] — a wider calendar scrolls instead of
     * turning into unreadable squares.
     */
    fun heatmapCell(maxWidth: Dp, gap: Dp, weeks: Int, minCell: Dp = HEATMAP_MIN_CELL): Dp {
        val columns = weeks.coerceAtLeast(1)
        val fitted = (maxWidth - gap * (columns - 1)) / columns
        return maxOf(minCell, fitted)
    }

    /** Grid height: the month-label strip plus [rows] cells and their gaps. */
    fun heatmapHeight(
        topOffset: Dp,
        cell: Dp,
        gap: Dp,
        rows: Int = 7
    ): Dp {
        val count = rows.coerceAtLeast(1)
        return topOffset + cell * count + gap * (count - 1)
    }

    /** Draw-and-tap pitch of one heatmap cell. */
    fun pitch(cellPx: Float, gapPx: Float): Float = cellPx + gapPx

    /** Grid column and row a heatmap tap lands in; tap above the grid is negative. */
    fun heatmapCellAt(
        x: Float,
        y: Float,
        topOffsetPx: Float,
        pitchPx: Float
    ): Pair<Int, Int> = Pair(
        floor(x / pitchPx).toInt(),
        floor((y - topOffsetPx) / pitchPx).toInt()
    )

    /**
     * The day index a heatmap cell points at, or null when the tap is outside
     * the grid's rows: a label-strip tap (negative row) must not fold back
     * into the previous column's last cell.
     */
    fun heatmapIndex(col: Int, row: Int, rows: Int = 7): Int? =
        if (row in 0 until rows) col * rows + row else null

    /** Index a horizontal tap lands in; left-gutter taps are negative, not zero. */
    fun barIndexAt(x: Float, left: Float, slot: Float): Int =
        floor((x - left) / slot).toInt()
}
