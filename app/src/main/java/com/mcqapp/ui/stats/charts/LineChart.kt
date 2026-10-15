package com.mcqapp.ui.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One polyline in a [LineChart]; null values are gaps. */
internal data class LineSeries(
    val values: List<Double?>,
    val color: Color,
    /** Shade the area under the line. */
    val fill: Boolean = false
)

/**
 * Multi-series line chart with optional fixed y-ceiling and a dashed
 * horizontal reference (e.g. desired retention). [labels] names the x points;
 * empty labels render [emptyText] instead of an axis.
 */
@Composable
internal fun LineChart(
    labels: List<String>,
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    /** y-axis ceiling, e.g. 1.0 for a rate; null derives a nice maximum. */
    fixedMax: Double? = null,
    referenceLine: Double? = null,
    yLabel: (Double) -> String = { formatCount(it) },
    emptyText: (@Composable () -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val grid = gridColor()
    val labelColor = axisLabelColor()

    val hasPoints = series.any { s -> s.values.any { it != null } }
    if (labels.isEmpty() || !hasPoints) {
        if (emptyText != null) {
            emptyText()
        } else {
            ChartEmpty()
        }
        return
    }

    val dataMax = series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 1.0
    val top = fixedMax ?: niceMax(dataMax.coerceAtLeast(1.0))
    val ticks = yTicks(top)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val leftPad = 40.dp.toPx()
        val rightPad = 8.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val topPad = 8.dp.toPx()
        val plotWidth = size.width - leftPad - rightPad
        val plotHeight = size.height - topPad - bottomPad
        val slot = plotWidth / labels.size

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

        // Optional dashed horizontal benchmark behind the lines.
        if (referenceLine != null && referenceLine in 0.0..top) {
            val y = topPad + plotHeight * (1f - (referenceLine / top).toFloat())
            drawLine(
                color = labelColor,
                start = Offset(leftPad, y),
                end = Offset(size.width - rightPad, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
        }

        fun xFor(index: Int): Float = leftPad + index * slot + slot / 2f
        fun yFor(value: Double): Float = topPad + plotHeight * (1f - (value / top).toFloat())

        // Fills first so strokes draw on top.
        series.forEach { s ->
            if (!s.fill) return@forEach
            val indices = s.values.indices.filter { s.values[it] != null }
            if (indices.isEmpty()) return@forEach
            val fillPath = Path()
            fillPath.moveTo(xFor(indices.first()), topPad + plotHeight)
            indices.forEach { i -> fillPath.lineTo(xFor(i), yFor(s.values[i]!!)) }
            fillPath.lineTo(xFor(indices.last()), topPad + plotHeight)
            fillPath.close()
            drawPath(fillPath, s.color.copy(alpha = 0.16f))
        }

        // Polylines, breaking at null gaps.
        series.forEach { s ->
            val path = Path()
            var started = false
            s.values.indices.forEach { i ->
                val value = s.values[i]
                if (value == null) {
                    started = false
                } else {
                    val x = xFor(i)
                    val y = yFor(value)
                    if (started) {
                        path.lineTo(x, y)
                    } else {
                        path.moveTo(x, y)
                        started = true
                    }
                }
            }
            drawPath(path, s.color, style = Stroke(2.dp.toPx()))
        }

        // Sparse x labels.
        val step = xAxisLabelStep(labels.size)
        labels.indices.forEach { index ->
            if (index % step == 0 || index == labels.size - 1) {
                val text = textMeasurer.measure(
                    labels[index],
                    TextStyle(fontSize = 9.sp, color = labelColor)
                )
                val x = (xFor(index) - text.size.width / 2f)
                    .coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, size.height - bottomPad + 4.dp.toPx()))
            }
        }
    }
}
