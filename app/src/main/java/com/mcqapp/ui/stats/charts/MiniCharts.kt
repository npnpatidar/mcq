package com.mcqapp.ui.stats.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * A donut of [slices] (value + colour, in draw order), with an optional
 * centre slot for the headline number. The legend is the section's job so its
 * labels stay at the render site.
 */
@Composable
internal fun DonutChart(
    slices: List<Pair<Double, Color>>,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
    center: (@Composable () -> Unit)? = null
) {
    val total = slices.sumOf { it.first }.coerceAtLeast(1e-9)
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            var startAngle = -90f
            slices.forEach { (value, color) ->
                val sweep = (value / total * 360.0).toFloat()
                if (sweep > 0f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep - min(1.5f, sweep * 0.1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Butt)
                    )
                    startAngle += sweep
                }
            }
        }
        if (center != null) {
            center()
        }
    }
}

/**
 * Predicted-vs-actual retention, the calibration plot: each point is a
 * predicted-probability band (x) against the observed pass rate (y), with the
 * dashed diagonal as perfection. Bubble area grows with the sample count.
 */
@Composable
internal fun CalibrationChart(
    points: List<Triple<Double, Double, Int>>,
    modifier: Modifier = Modifier
) {
    val grid = gridColor()
    val labelColor = axisLabelColor()
    val primary = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxWidth()) {
        val side = minOf(size.width, 240.dp.toPx())
        val leftPad = (size.width - side).coerceAtLeast(0f) / 2f
        val bottomPad = 18.dp.toPx()
        val plot = side - 8.dp.toPx()
        val origin = Offset(leftPad + 4.dp.toPx(), bottomPad + 0f)

        // Frame and diagonal.
        drawLine(grid, origin, origin + Offset(plot, 0f), 1f)
        drawLine(grid, origin, origin + Offset(0f, -plot), 1f)
        drawLine(
            labelColor,
            origin + Offset(0f, -plot),
            origin + Offset(plot, 0f),
            2f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        )
        listOf(0.0, 0.5, 1.0).forEach { fraction ->
            val tick = textMeasurer.measure(
                formatPct(fraction),
                androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = labelColor)
            )
            drawText(tick, topLeft = Offset(origin.x - tick.size.width - 4.dp.toPx(), origin.y - plot * fraction.toFloat() - tick.size.height / 2f))
        }
        points.forEach { (predicted, actual, count) ->
            val radius = (6.dp.toPx() * (1 + count / 20f)).coerceAtMost(16.dp.toPx())
            drawCircle(
                color = primary.copy(alpha = 0.75f),
                radius = radius,
                center = origin + Offset(
                    (plot * predicted.toFloat()),
                    (-plot * actual.toFloat())
                )
            )
        }
    }
}
