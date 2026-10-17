package com.mcqapp.ui.stats.sections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.ui.stats.charts.CalibrationChart
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.LegendDot
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.MeterBar
import com.mcqapp.ui.stats.charts.formatPct
import kotlin.math.roundToInt

/**
 * Tab 2: retention over time with the desired-retention reference, FSRS
 * calibration (predicted vs actual recall), and retention broken down by
 * paper/category and weekday.
 */
@Composable
internal fun RetentionSection(bundle: RetentionData) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "trend") { TrendCard(bundle) }
        item(contentType = "calibration") { CalibrationCard(bundle) }
        if (bundle.groupRetention.isNotEmpty()) {
            item(contentType = "group-retention") { GroupRetentionCard(bundle) }
        }
        item(contentType = "weekday") { WeekdayCard(bundle) }
    }
}

@Composable
private fun TrendCard(bundle: RetentionData) {
    val trend = bundle.retentionTrend
    val labels = trend.points.map { it.label }
    ChartCard(
        testTag = "stats-retention-trend",
        title = { ChartTitle(R.string.stats_retention_trend) },
        subtitle = { ChartSubtitle(R.string.stats_retention_trend_help) }
    ) {
        LineChart(
            labels = labels,
            series = listOf(
                LineSeries(
                    values = trend.points.map { it.value },
                    color = MaterialTheme.colorScheme.primary,
                    fill = true
                ),
                LineSeries(
                    values = trend.rolling.map { it.value },
                    color = MaterialTheme.colorScheme.tertiary
                )
            ),
            fixedMax = 1.0,
            referenceLine = trend.desiredRetention,
            yLabel = { formatPct(it) },
            emptyText = { ChartEmpty(R.string.stats_no_data) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendEntry(MaterialTheme.colorScheme.primary, R.string.stats_actual)
            LegendEntry(MaterialTheme.colorScheme.tertiary, R.string.stats_rolling)
            LegendEntry(
                MaterialTheme.colorScheme.onSurfaceVariant,
                R.string.stats_desired_retention
            )
        }
    }
}

@Composable
private fun LegendEntry(color: Color, @StringRes labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(color)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CalibrationCard(bundle: RetentionData) {
    val calibration = bundle.calibration
    ChartCard(
        testTag = "stats-calibration",
        title = { ChartTitle(R.string.stats_calibration) },
        subtitle = { ChartSubtitle(R.string.stats_calibration_help) }
    ) {
        if (calibration.samples == 0) {
            ChartEmpty(R.string.stats_calibration_empty)
            return@ChartCard
        }
        CalibrationChart(
            points = calibration.bins.map { bin ->
                Triple(bin.predictedMean, bin.actualRate ?: 0.0, bin.count)
            }
        )
        Text(
            stringResource(
                R.string.stats_calibration_summary,
                formatPct(calibration.predictedMean ?: 0.0),
                formatPct(calibration.actualMean ?: 0.0),
                calibration.samples,
                (calibration.brier ?: 0.0).roundTo3()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private fun Double.roundTo3(): String = String.format(java.util.Locale.ENGLISH, "%.3f", this)

@Composable
private fun GroupRetentionCard(bundle: RetentionData) {
    ChartCard(
        testTag = "stats-group-retention",
        title = { ChartTitle(R.string.stats_by_group) },
        subtitle = { ChartSubtitle(R.string.stats_by_group_help) }
    ) {
        bundle.groupRetention.forEach { group ->
            RetentionBar(
                label = group.label,
                reviews = group.reviews,
                retention = group.retention
            )
        }
    }
}

@Composable
private fun WeekdayCard(bundle: RetentionData) {
    ChartCard(
        testTag = "stats-weekday",
        title = { ChartTitle(R.string.stats_by_weekday) }
    ) {
        val dayLabels = listOf(
            R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed,
            R.string.weekday_thu, R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun
        )
        bundle.weekdayRetention.forEach { day ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(dayLabels[day.weekday]),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(Modifier.weight(1f)) {
                    MeterBar(
                        fraction = day.retention ?: 0.0,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(0.dp))
                Text(
                    retentionValue(day.retention, day.reviews),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun RetentionBar(label: String, reviews: Int, retention: Double?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                retentionValue(retention, reviews),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        MeterBar(
            fraction = retention ?: 0.0,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** "92% · 148" or an em dash with the review count. */
private fun retentionValue(retention: Double?, reviews: Int): String =
    (retention?.let { formatPct(it) } ?: "—") + " · " + reviews
