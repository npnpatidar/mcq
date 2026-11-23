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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.ui.theme.contrastRatio
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
    /** An optional human-readable summary spoken for the whole card. */
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // The tag used to be the card's contentDescription, so TalkBack read
    // "stats-heatmap" instead of the chart. A tag is a test hook, not a label:
    // keep it on testTag and only set contentDescription when a real summary
    // is supplied. The visible title/subtitle stay readable either way.
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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

/**
 * Canvas charts draw into one semantics node, so a screen reader cannot reach
 * the bars or points a sighted user reads off the axes and tooltip. Build one
 * spoken sentence from the chart kind, one named group per series, a
 * label/value pair per point, and an optional note.
 *
 * A multi-series line chart used to collapse to its first series only, which
 * silently dropped the other lines. Each series is now named and bounded
 * independently to the latest [CHART_SUMMARY_POINTS] points: a 365-day calendar
 * would otherwise produce a sentence nobody could listen to, and the omitted
 * counts are spoken so the summary never implies it is complete.
 */
internal const val CHART_SUMMARY_POINTS = 8

/** One series' contribution to a spoken summary. A blank [name] means "do not
 * speak a group label" — single-series charts keep their old flat wording. */
internal data class ChartSeriesSummary(
    val name: String = "",
    val points: List<Pair<String, String>> = emptyList()
)

/** The bounded view of one series, with the number of points left out. */
internal data class SeriesWindow(
    val name: String,
    val shown: List<Pair<String, String>>,
    val hidden: Int
)

/**
 * Pure bounding step, shared with the JVM tests: each series keeps only its
 * last [maxPoints] points and reports how many it dropped. Bounding *per
 * series* is what keeps every line represented after the old first-only bug.
 */
internal fun windowSeries(
    series: List<ChartSeriesSummary>,
    maxPoints: Int = CHART_SUMMARY_POINTS
): List<SeriesWindow> = series.map { s ->
    val shown = s.points.takeLast(maxPoints)
    SeriesWindow(s.name, shown, s.points.size - shown.size)
}

/** The unscaled canvas height used by the line and stacked-bar charts. */
internal const val CHART_BASE_HEIGHT_DP = 200f

/**
 * Canvas height in dp for the current font scale. Axis labels are in `sp`, so
 * they grow with the font scale; a fixed `200.dp` canvas clipped them at large
 * text sizes (ST6). Clamped to 1x–2x so an extreme accessibility scale cannot
 * make a chart dominate the screen.
 */
internal fun chartHeightDp(fontScale: Float): Float =
    CHART_BASE_HEIGHT_DP * fontScale.coerceIn(1f, 2f)

/**
 * One named series per stacked-bar segment, each carrying that segment's value
 * at every bar. Feeding these to [chartSummary] makes a screen reader announce
 * the segments instead of only the bar total (ST6). Bars with no value for a
 * segment are skipped, so a truncated `segmentCount` cannot invent data.
 */
internal fun stackedSegmentSeries(
    barLabels: List<String>,
    barSegments: List<List<Double>>,
    segmentNames: List<String>,
    labelValue: (Double) -> String
): List<ChartSeriesSummary> = segmentNames.indices.map { segment ->
    ChartSeriesSummary(
        name = segmentNames[segment],
        points = barLabels.mapIndexedNotNull { barIndex, label ->
            barSegments.getOrNull(barIndex)
                ?.getOrNull(segment)
                ?.let { label to labelValue(it) }
        }
    )
}

@Composable
internal fun chartSummary(
    @StringRes kindRes: Int,
    series: List<ChartSeriesSummary>,
    note: String = ""
): String {
    val windows = windowSeries(series)
    // A plain loop, not buildString {}: stringResource is a composable and
    // must be called directly in the composable body, not from an inline
    // lambda.
    val parts = ArrayList<String>(windows.size + 2)
    if (note.isNotEmpty()) parts.add(note)
    for (window in windows) {
        val rendered = window.shown.map { (label, value) ->
            stringResource(R.string.a11y_chart_point, label, value)
        }
        if (rendered.isEmpty()) continue
        if (window.name.isBlank()) {
            parts.addAll(rendered)
        } else {
            parts.add(
                stringResource(
                    R.string.a11y_chart_series_points,
                    window.name,
                    rendered.joinToString(", ")
                )
            )
        }
    }
    val hidden = windows.sumOf { it.hidden }
    if (hidden > 0) parts.add(stringResource(R.string.a11y_chart_more, hidden))
    return stringResource(R.string.a11y_chart_summary, stringResource(kindRes), parts.joinToString(". "))
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

/**
 * WCAG 2.1 SC 1.4.11: a graphical object that conveys information (a chart
 * series) needs 3:1 against the colour next to it, here the card surface.
 */
internal const val CHART_MIN_NON_TEXT_CONTRAST = 3.0

/**
 * Custom themes can wash out any role against `surface`, so every series role
 * is checked. Keep the role when it clears 3:1; otherwise fall back to a
 * near-on-surface role so the series stays visible by lightness as well as
 * hue. Returns [candidate] when it is already safe.
 */
internal fun chartSeriesColor(candidate: Color, background: Color, fallback: Color): Color =
    if (contrastRatio(candidate, background) >= CHART_MIN_NON_TEXT_CONTRAST) candidate else fallback

/** The palette charts cycle through, in legend order. */
@Composable
internal fun seriesColors(): List<Color> {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // All six roles, not just the last two, go through the contrast guard: a
    // custom theme can wash out primary/tertiary/secondary/error just as easily
    // as outline/inversePrimary, and a series that vanishes against the card
    // surface is unusable whatever its role.
    return listOf(
        chartSeriesColor(MaterialTheme.colorScheme.primary, surface, onSurface),
        chartSeriesColor(MaterialTheme.colorScheme.tertiary, surface, onSurface),
        chartSeriesColor(MaterialTheme.colorScheme.secondary, surface, onSurface),
        chartSeriesColor(MaterialTheme.colorScheme.error, surface, onSurface),
        chartSeriesColor(MaterialTheme.colorScheme.outline, surface, onSurfaceVariant),
        chartSeriesColor(MaterialTheme.colorScheme.inversePrimary, surface, onSurface)
    )
}

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

/** A bucket below this many reviews is too thin to carry a full-weight point. */
internal const val SMALL_BUCKET_REVIEWS = 5

/**
 * A line point's spoken text with its sample size, when one is known. A 100%
 * bucket of one review must not sound the same as a 100% bucket of a hundred.
 * [reviewUnit] is the localized unit supplied by the caller (plural form).
 */
internal fun linePointText(
    value: String,
    count: Int,
    singularUnit: String,
    pluralUnit: String
): String = if (count <= 0) {
    value
} else {
    "$value · $count ${if (count == 1) singularUnit else pluralUnit}"
}

/** True for a non-empty bucket whose sample is too thin for a solid marker. */
internal fun isSmallBucket(count: Int, threshold: Int = SMALL_BUCKET_REVIEWS): Boolean =
    count in 1 until threshold

internal fun formatCount(value: Double): String =
    if (value >= 10 || value == value.roundToInt().toDouble()) {
        value.roundToInt().toString()
    } else {
        String.format(java.util.Locale.ENGLISH, "%.1f", value)
    }

/** 59 → "59 s", 90 → "1m", 5400 → "1 h 30 m"; the study-time axis label.
 *
 * Whole units only: 90 seconds is one minute, not 1.5m. The spacing before a
 * unit is deliberate and pinned by StatsChartKitTest. */
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
internal fun MeterBar(
    fraction: Double,
    color: Color,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                }
            )
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
