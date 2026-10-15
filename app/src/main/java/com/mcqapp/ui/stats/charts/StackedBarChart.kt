package com.mcqapp.ui.stats.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcqapp.domain.stats.StackedPoint
import kotlin.math.roundToInt

/**
 * Stacked vertical bars with an optional right-axis overlay line scaled to
 * 0..1 (a pass rate or a cumulative percentage), Anki's Reviews graph shape.
 * Tap a bar for its exact figures.
 *
 * [segmentCount] truncates each bar's segments; [overlay] aligns with
 * [points]. Empty [points] renders [emptyText] instead of an axis.
 */
@Composable
internal fun StackedBarChart(
    points: List<StackedPoint>,
    modifier: Modifier = Modifier,
    segmentCount: Int = points.maxOfOrNull { it.segments.size } ?: 0,
    yLabel: (Double) -> String = { formatCount(it) },
    overlay: List<Double?>? = null,
    /** The overlay line's right-axis ceiling; 1.0 for a rate, counts otherwise. */
    overlayMax: Double = 1.0,
    overlayLabel: (Double) -> String = { formatPct(it) },
    emptyText: (@Composable () -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val colors = seriesColors()
    val grid = gridColor()
    val labelColor = axisLabelColor()
    val overlayColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.surface
    var selected by remember(points) { mutableIntStateOf(-1) }

    if (points.isEmpty()) {
        if (emptyText != null) {
            emptyText()
        } else {
            ChartEmpty()
        }
        return
    }

    val totals = points.map { bar -> bar.segments.take(segmentCount).sum() }
    val top = niceMax((totals.maxOrNull() ?: 1.0).coerceAtLeast(1.0))
    val ticks = yTicks(top)
    val hasOverlay = overlay != null && overlay.size == points.size
    val overlayCeiling = if (hasOverlay) {
        niceMax((overlay!!.filterNotNull().maxOrNull() ?: 1.0).coerceAtLeast(overlayMax))
    } else {
        1.0
    }

    // The tap handler recomputes exactly the geometry the draw block uses;
    // both scopes are Density-aware so the dp paddings agree.
    fun leftPad(density: Float) = 40f * density
    fun rightPad(density: Float) = if (hasOverlay) 38f * density else 8f * density

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(points, overlay) {
                detectTapGestures { tap ->
                    val left = leftPad(density)
                    val plotWidth = size.width - left - rightPad(density)
                    val slot = plotWidth / points.size
                    val index = ((tap.x - left) / slot).toInt()
                    selected = if (index in points.indices) index else -1
                }
            }
    ) {
        val leftPad = 40.dp.toPx()
        val rightPad = if (hasOverlay) 38.dp.toPx() else 8.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val topPad = 8.dp.toPx()
        val plotWidth = size.width - leftPad - rightPad
        val plotHeight = size.height - topPad - bottomPad
        val slot = plotWidth / points.size
        val barWidth = (slot * 0.68f).coerceAtMost(28.dp.toPx())

        // Grid + left axis labels.
        ticks.forEach { tick ->
            val y = topPad + plotHeight * (1f - (tick / top).toFloat())
            drawLine(grid, Offset(leftPad, y), Offset(size.width - rightPad, y), 1f)
            val text = textMeasurer.measure(
                yLabel(tick),
                TextStyle(fontSize = 9.sp, color = labelColor)
            )
            drawText(text, topLeft = Offset(0f, y - text.size.height / 2f))
        }

        // Bars, stacked bottom-up.
        points.forEachIndexed { index, bar ->
            val x = leftPad + index * slot + (slot - barWidth) / 2f
            var yCursor = topPad + plotHeight
            bar.segments.take(segmentCount).forEachIndexed { segmentIndex, value ->
                if (value > 0.0) {
                    val h = plotHeight * (value / top).toFloat()
                    yCursor -= h
                    drawRoundRect(
                        color = colors[segmentIndex % colors.size],
                        topLeft = Offset(x, yCursor),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
            if (index == selected) {
                drawRoundRect(
                    color = overlayColor,
                    topLeft = Offset(x - 2.dp.toPx(), topPad),
                    size = Size(barWidth + 4.dp.toPx(), plotHeight),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(1.5.dp.toPx())
                )
            }
        }

        // Right-axis overlay line, scaled to its own ceiling with gaps where
        // the value is unknown.
        if (hasOverlay) {
            val path = Path()
            var started = false
            points.indices.forEach { index ->
                val value = overlay!![index] ?: return@forEachIndexed
                val x = leftPad + index * slot + slot / 2f
                val y = topPad + plotHeight * (1f - (value / overlayCeiling).toFloat())
                if (started) {
                    path.lineTo(x, y)
                } else {
                    path.moveTo(x, y)
                    started = true
                }
            }
            drawPath(path, overlayColor, style = Stroke(2.dp.toPx()))
            listOf(0.0, 0.5, 1.0).forEach { fraction ->
                val y = topPad + plotHeight * (1f - fraction.toFloat())
                val text = textMeasurer.measure(
                    overlayLabel(fraction * overlayCeiling),
                    TextStyle(fontSize = 9.sp, color = labelColor)
                )
                drawText(
                    text,
                    topLeft = Offset(
                        size.width - rightPad + 4.dp.toPx(),
                        y - text.size.height / 2f
                    )
                )
            }
        }

        // Sparse x labels.
        val step = xAxisLabelStep(points.size)
        points.indices.forEach { index ->
            if (index % step == 0 || index == points.size - 1) {
                val text = textMeasurer.measure(
                    points[index].label,
                    TextStyle(fontSize = 9.sp, color = labelColor)
                )
                val x = (leftPad + index * slot + slot / 2f - text.size.width / 2f)
                    .coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, size.height - bottomPad + 4.dp.toPx()))
            }
        }

        // Tooltip for the selected bar.
        if (selected in points.indices) {
            val bar = points[selected]
            val total = bar.segments.take(segmentCount).sum().roundToInt()
            val overlayText = if (hasOverlay && overlay!![selected] != null) {
                "\n" + overlayLabel(overlay[selected]!!)
            } else {
                ""
            }
            val text = textMeasurer.measure(
                "${bar.label}\n$total$overlayText",
                TextStyle(fontSize = 10.sp, color = overlayColor)
            )
            val boxWidth = text.size.width + 16.dp.toPx()
            val boxHeight = text.size.height + 10.dp.toPx()
            val cx = leftPad + selected * slot + slot / 2f
            val topLeft = Offset(
                (cx - boxWidth / 2f).coerceIn(0f, size.width - boxWidth),
                topPad + 4.dp.toPx()
            )
            drawRoundRect(
                color = tooltipBg,
                topLeft = topLeft,
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
            drawRoundRect(
                color = grid,
                topLeft = topLeft,
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(1f)
            )
            drawText(text, topLeft = topLeft + Offset(8.dp.toPx(), 5.dp.toPx()))
        }
    }
}
