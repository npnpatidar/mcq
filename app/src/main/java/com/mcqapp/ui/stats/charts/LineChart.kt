package com.mcqapp.ui.stats.charts

import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcqapp.R

/** One polyline in a [LineChart]; null values are gaps. */
internal data class LineSeries(
    val values: List<Double?>,
    val color: Color,
    /** Shade the area under the line. */
    val fill: Boolean = false,
    /** Spoken name for this series, e.g. "introduced". Blank falls back to
     *  "Series N" so a caller without a label still names every line. */
    val name: String = "",
    /** Sample size behind each point; appended to the spoken/tooltip text. */
    val counts: List<Int> = emptyList(),
    /** Buckets below this size are drawn as faded, hollow points. */
    val deEmphasiseBelow: Int? = null
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
    val surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    // Format words consumed inside a canvas-built spoken summary. Resolved with
    // stringResource at the composable level so they stay configuration-aware.
    val reviewUnit = chartUnit(R.string.stats_chart_reviews_unit)
    val reviewUnitOne = chartUnit(R.string.stats_chart_review_unit)
    // Axis labels are in sp; scale the canvas so large text cannot clip (ST6).
    val chartHeight = chartHeightDp(LocalDensity.current.fontScale).dp

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
    // One named group per series, each with a label/value pair for every x
    // point it has. The old code folded all series into a single value and kept
    // only the first, so a second or third line was never spoken at all. Each
    // group is bounded separately, so every series survives truncation.
    val spokenSeries = ArrayList<ChartSeriesSummary>(series.size)
    series.forEachIndexed { index, s ->
        val name = s.name.ifBlank { stringResource(R.string.a11y_chart_series, index + 1) }
        val points = labels.mapIndexedNotNull { pointIndex, label ->
            s.values.getOrNull(pointIndex)?.let { value ->
                val count = s.counts.getOrNull(pointIndex) ?: 0
                label to linePointText(yLabel(value), count, reviewUnitOne, reviewUnit)
            }
        }
        spokenSeries.add(ChartSeriesSummary(name, points))
    }
    val summary = chartSummary(kindRes = R.string.a11y_chart_line, series = spokenSeries)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .semantics { contentDescription = summary }
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
            // Split into contiguous non-null runs; the fill must not bridge gaps.
            val runs = mutableListOf<MutableList<Int>>()
            var current: MutableList<Int>? = null
            s.values.indices.forEach { i ->
                if (s.values[i] != null) {
                    current = (current ?: mutableListOf()).apply { add(i) }
                } else {
                    if (current != null) { runs.add(current!!); current = null }
                }
            }
            if (current != null) runs.add(current!!)
            runs.forEach { run ->
                if (run.isEmpty()) return@forEach
                val fillPath = Path()
                fillPath.moveTo(xFor(run.first()), topPad + plotHeight)
                run.forEach { i -> fillPath.lineTo(xFor(i), yFor(s.values[i]!!)) }
                fillPath.lineTo(xFor(run.last()), topPad + plotHeight)
                fillPath.close()
                drawPath(fillPath, s.color.copy(alpha = 0.16f))
            }
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

        // A thin bucket is still plotted, but as a faded hollow point: a 100%
        // day built from one review cannot look as solid as one built from
        // fifty. The count is always in the spoken label above.
        series.forEach { s ->
            if (s.counts.isEmpty()) return@forEach
            val threshold = s.deEmphasiseBelow ?: return@forEach
            s.values.indices.forEach { i ->
                val value = s.values[i] ?: return@forEach
                val count = s.counts.getOrNull(i) ?: 0
                val centre = Offset(xFor(i), yFor(value))
                if (isSmallBucket(count, threshold)) {
                    drawCircle(surfaceColor, 5.dp.toPx(), centre)
                    drawCircle(s.color.copy(alpha = 0.45f), 2.5.dp.toPx(), centre)
                } else {
                    drawCircle(s.color, 3.dp.toPx(), centre)
                }
            }
        }

        // Sparse x labels. Measure first so the last label can push out a
        // neighbour it would overlap instead of drawing on top of it.
        val measured = labels.map {
            textMeasurer.measure(it, TextStyle(fontSize = 9.sp, color = labelColor))
        }
        val lefts = measured.mapIndexed { index, text ->
            (xFor(index) - text.size.width / 2f)
                .coerceIn(0f, size.width - text.size.width)
        }
        val drawn = xAxisLabelIndices(
            lefts = lefts,
            widths = measured.map { it.size.width.toFloat() },
            gap = 4.dp.toPx()
        )
        drawn.forEach { index ->
            drawText(
                measured[index],
                topLeft = Offset(lefts[index], size.height - bottomPad + 4.dp.toPx())
            )
        }
    }
}

/** Resolve a localized unit word for the canvas-built spoken summary. */
@Composable
private fun chartUnit(@StringRes unitRes: Int): String = stringResource(unitRes)
