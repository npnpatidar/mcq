package com.mcqapp.ui.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcqapp.R
import com.mcqapp.domain.stats.HeatmapDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * GitHub-style calendar of reviews per study day, Anki's Calendar graph.
 * Columns are weeks, oldest first; each row is a fixed offset from the
 * window's first weekday (`index % 7`), so rows do not track Monday..Sunday
 * and there is deliberately no weekday header. Intensity ramps to the
 * strongest colour at four or more reviews. Tap a cell for the day's exact
 * count (consolidated_bugs.md E5: the old doc claimed weekday rows).
 */
@Composable
internal fun HeatmapChart(
    days: List<HeatmapDay>,
    modifier: Modifier = Modifier,
    tooltipCount: (Int) -> String
) {
    val textMeasurer = rememberTextMeasurer()
    val emptyColor = gridColor()
    val strong = MaterialTheme.colorScheme.primary
    val labelColor = axisLabelColor()
    val tooltipBg = MaterialTheme.colorScheme.surface
    val tooltipText = MaterialTheme.colorScheme.onSurface
    var selected by remember(days) { mutableIntStateOf(-1) }

    val weeks = ChartGeometry.heatmapWeeks(days.size)
    val maxReviews = (days.maxOfOrNull { it.reviews } ?: 0).coerceAtLeast(1)
    // Month names follow the selected in-app locale, not hard-coded English
    // (ST2). The formatter patterns are language-neutral; the locale supplies
    // the month labels.
    val chartLocale = LocalConfiguration.current.locales[0]
    val dateFmt = DateTimeFormatter.ofPattern("d MMM", chartLocale)
    val monthFmt = DateTimeFormatter.ofPattern("MMM", chartLocale)

    fun dayDate(day: HeatmapDay): LocalDate =
        Instant.ofEpochMilli(day.dayStart).atZone(ZoneId.systemDefault()).toLocalDate()

    // The calendar is one canvas; expose the day/count pairs plus a total so
    // TalkBack can report the data instead of the old test-tag string.
    val summary = chartSummary(
        kindRes = R.string.a11y_chart_calendar,
        series = listOf(
            ChartSeriesSummary(
                points = days.map { day -> dayDate(day).format(dateFmt) to tooltipCount(day.reviews) }
            )
        ),
        note = stringResource(
            R.string.a11y_chart_total,
            days.sumOf { it.reviews }.toString()
        )
    )

    // BoxWithConstraints must stay outside the scroll container: inside one,
    // maxWidth is infinite and the cell would never be sized to the viewport.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = ChartGeometry.HEATMAP_GAP
        val cell = ChartGeometry.heatmapCell(maxWidth, gap, weeks)
        val contentWidth = ChartGeometry.heatmapWidth(cell, weeks, gap)
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Canvas(
                modifier = Modifier
                    .testTag("stats-heatmap-canvas")
                    .width(contentWidth)
                    .height(
                        ChartGeometry.heatmapHeight(
                            ChartGeometry.HEATMAP_TOP_OFFSET,
                            cell,
                            gap,
                            ChartGeometry.HEATMAP_ROWS
                        )
                    )
                    .semantics { contentDescription = summary }
                    .pointerInput(days, gap, cell) {
                        detectTapGestures { tap ->
                            // Tap offsets are local to the canvas, which spans
                            // the full content width, so no scroll offset is
                            // added here.
                            val pitch = ChartGeometry.pitch(cell.toPx(), gap.toPx())
                            val topOffset = ChartGeometry.HEATMAP_TOP_OFFSET.toPx()
                            val (col, row) = ChartGeometry.heatmapCellAt(tap.x, tap.y, topOffset, pitch)
                            val index = ChartGeometry.heatmapIndex(col, row, ChartGeometry.HEATMAP_ROWS)
                            selected = if (index != null && index in days.indices) index else -1
                        }
                    }
            ) {
                val gapPx = gap.toPx()
                val cellPx = cell.toPx()
                val pitch = cellPx + gapPx
                val topOffset = ChartGeometry.HEATMAP_TOP_OFFSET.toPx()
                val corner = CornerRadius(2.dp.toPx())
                val rows = ChartGeometry.HEATMAP_ROWS

                // Month labels above the first column of each new month.
                var lastMonth = -1
                days.forEachIndexed { index, day ->
                    val date = dayDate(day)
                    if (date.monthValue != lastMonth && index % rows == 0) {
                        lastMonth = date.monthValue
                        val text = textMeasurer.measure(date.format(monthFmt), TextStyle(fontSize = 9.sp, color = labelColor))
                        val x = (index / rows) * pitch
                        drawText(text, topLeft = Offset(x, 0f))
                    }
                }

                days.forEachIndexed { index, day ->
                    val col = index / rows
                    val row = index % rows
                    val intensity = (day.reviews.toFloat() / maxReviews.coerceAtMost(4)).coerceIn(0f, 1f)
                    val color = if (day.reviews == 0) emptyColor else lerp(strong.copy(alpha = 0.35f), strong, intensity)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(col * pitch, topOffset + row * pitch),
                        size = Size(cellPx, cellPx),
                        cornerRadius = corner
                    )
                }

                if (selected in days.indices) {
                    val day = days[selected]
                    val text = textMeasurer.measure(
                        dayDate(day).format(dateFmt) + " \u00b7 " + tooltipCount(day.reviews),
                        TextStyle(fontSize = 10.sp, color = tooltipText)
                    )
                    val boxWidth = text.size.width + 16.dp.toPx()
                    val boxHeight = text.size.height + 10.dp.toPx()
                    val col = selected / rows
                    // Clamp to the whole canvas, which spans every day: the
                    // scroll container reveals the rest, so no clamping to the
                    // currently visible window is required.
                    val topLeft = Offset(
                        (col * pitch - boxWidth / 2f).coerceIn(0f, size.width - boxWidth),
                        topOffset + 2.dp.toPx()
                    )
                    drawRoundRect(tooltipBg, topLeft, Size(boxWidth, boxHeight), CornerRadius(4.dp.toPx()))
                    drawRoundRect(
                        emptyColor, topLeft, Size(boxWidth, boxHeight),
                        CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1f)
                    )
                    drawText(text, topLeft = topLeft + Offset(8.dp.toPx(), 5.dp.toPx()))
                }
            }
        }
    }
}
