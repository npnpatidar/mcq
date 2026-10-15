package com.mcqapp.ui.stats.charts

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcqapp.domain.stats.HeatmapDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GitHub-style calendar of reviews per study day, Anki's Calendar graph.
 * Columns are weeks, rows Monday..Sunday; intensity ramps to the strongest
 * colour at four or more reviews. Tap a cell for the day's exact count.
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

    val weeks = ((days.size + 6) / 7).coerceAtLeast(1)
    val maxReviews = (days.maxOfOrNull { it.reviews } ?: 0).coerceAtLeast(1)
    val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    val monthFmt = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = 2.dp
        val cell = ChartGeometry.heatmapCell(maxWidth, gap, weeks)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartGeometry.heatmapHeight(14.dp, cell, gap))
                .pointerInput(days, gap, cell) {
                    detectTapGestures { tap ->
                        val pitch = ChartGeometry.pitch(cell.toPx(), gap.toPx())
                        val topOffset = 14.dp.toPx()
                        val (col, row) = ChartGeometry.heatmapCellAt(tap.x, tap.y, topOffset, pitch)
                        val index = col * 7 + row
                        selected = if (index in days.indices) index else -1
                    }
                }
        ) {
            val gapPx = gap.toPx()
            val cellPx = cell.toPx()
            val pitch = cellPx + gapPx
            val topOffset = 14.dp.toPx()
            val corner = CornerRadius(2.dp.toPx())

            fun dayDate(day: HeatmapDay): LocalDate =
                Instant.ofEpochMilli(day.dayStart).atZone(ZoneId.systemDefault()).toLocalDate()

            // Month labels above the first column of each new month.
            var lastMonth = -1
            days.forEachIndexed { index, day ->
                val date = dayDate(day)
                if (date.monthValue != lastMonth && index % 7 == 0) {
                    lastMonth = date.monthValue
                    val text = textMeasurer.measure(date.format(monthFmt), TextStyle(fontSize = 9.sp, color = labelColor))
                    val x = (index / 7) * pitch
                    drawText(text, topLeft = Offset(x, 0f))
                }
            }

            days.forEachIndexed { index, day ->
                val col = index / 7
                val row = index % 7
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
                val col = selected / 7
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
