package com.mcqapp.ui.stats.charts

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The shared frame every statistics graph sits in: a card with a heading and
 * optional subtitle slot. Text slots are composables so callers resolve
 * strings at the render site.
 */
@Composable
internal fun ChartCard(
    testTag: String,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .semantics { contentDescription = testTag }
        ) {
            title()
            if (subtitle != null) {
                subtitle()
            }
            content()
        }
    }
}

/** The section heading style used inside [ChartCard]. */
@Composable
internal fun ChartTitle(@StringRes titleRes: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(bottom = 2.dp)
    )
}

@Composable
internal fun ChartSubtitle(@StringRes subtitleRes: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(subtitleRes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

/** A chart's "not enough data yet" placeholder. */
@Composable
internal fun ChartEmpty(@StringRes textRes: Int = R.string.stats_no_data, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One legend entry's colour dot; sections pair it with their own label. */
@Composable
internal fun LegendDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
internal fun LegendRow(entries: List<Pair<Color, @Composable RowScope.() -> Unit>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        entries.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(color)
                label()
            }
        }
    }
}

/** The palette charts cycle through, in legend order. */
@Composable
internal fun seriesColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.error,
    MaterialTheme.colorScheme.outline,
    MaterialTheme.colorScheme.inversePrimary
)

@Composable
internal fun gridColor(): Color = MaterialTheme.colorScheme.surfaceVariant

@Composable
internal fun axisLabelColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * The smallest 1/2/5×10ᵏ value at or above [value], so a bar chart's top
 * gridline lands on a number a human would have chosen.
 */
internal fun niceMax(value: Double): Double {
    if (value <= 0.0) return 1.0
    // floor, not truncate: log10(0.5) = -0.3 truncates to 0 and returned 1.0
    // for a value below 1 (consolidated_bugs.md E4). Every current caller
    // coerces >= 1, so this is the latent contract only.
    val exponent = floor(log10(value)).toInt()
    val base = 10.0.pow(exponent)
    val scaled = value / base
    val nice = when {
        scaled <= 1.0 -> 1.0
        scaled <= 2.0 -> 2.0
        scaled <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

/** Even tick positions from zero to [max]; [max] must be a [niceMax] result. */
internal fun yTicks(max: Double, count: Int = 4): List<Double> {
    if (max <= 0.0) return listOf(0.0)
    val step = max / count
    return (0..count).map { it * step }
}

internal fun formatPct(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

internal fun formatCount(value: Double): String =
    if (value >= 10 || value == value.roundToInt().toDouble()) {
        value.roundToInt().toString()
    } else {
        String.format(java.util.Locale.ENGLISH, "%.1f", value)
    }

/** 59 → "59s", 90 → "1.5m", 5400 → "1h 30m"; the study-time axis label. */
internal fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    return when {
        total < 60 -> "$total s"
        total < 3600 -> {
            val minutes = total / 60
            "${minutes}m"
        }
        else -> {
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            if (minutes == 0) "${hours}h" else "$hours h $minutes m"
        }
    }
}

internal fun formatSeconds(value: Double): String = "${value.roundToInt()}s"

/**
 * The rounded meter behind a ranked row: track in surfaceVariant, fill in
 * [color] at [fraction] of the width.
 */
@Composable
internal fun MeterBar(fraction: Double, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(gridColor())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}

/** How many x-axis labels to draw for [count] bars: roughly one per sixth. */
internal fun xAxisLabelStep(count: Int): Int = ceil(count / 6.0).toInt().coerceAtLeast(1)

/**
 * Which x-axis labels actually fit. [lefts] are each label's clamped left
 * edge, [widths] its measured width. Sparse labels are kept left-to-right;
 * the always-drawn last label wins any collision, because it names the most
 * recent point. Without this the last label could butt into its neighbour
 * when a multi-line label is wider than the step.
 */
internal fun xAxisLabelIndices(
    lefts: List<Float>,
    widths: List<Float>,
    gap: Float
): List<Int> {
    if (lefts.isEmpty() || lefts.size != widths.size) return emptyList()
    val last = lefts.lastIndex
    val step = xAxisLabelStep(lefts.size)
    val kept = mutableListOf<Int>()
    for (index in lefts.indices) {
        if (index % step != 0 && index != last) continue
        val left = lefts[index]
        if (index == last) {
            while (kept.isNotEmpty()) {
                val previous = kept.last()
                val previousRight = lefts[previous] + widths[previous]
                if (previousRight + gap > left) kept.removeAt(kept.lastIndex) else break
            }
            kept.add(index)
        } else {
            val previous = kept.lastOrNull()
            if (previous == null || lefts[previous] + widths[previous] + gap <= left) {
                kept.add(index)
            }
        }
    }
    return kept
}
