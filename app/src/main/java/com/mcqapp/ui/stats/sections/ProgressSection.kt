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
import com.mcqapp.domain.stats.ProgressComparison
import com.mcqapp.domain.stats.ProgressData
import com.mcqapp.domain.stats.ProgressSlice
import com.mcqapp.ui.stats.KpiGrid
import com.mcqapp.ui.stats.KpiItem
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.LegendDot
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.formatCount
import com.mcqapp.ui.stats.charts.formatPct
import com.mcqapp.ui.stats.charts.formatSeconds
import com.mcqapp.ui.stats.charts.seriesColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Progress tab (plan §5): introductions, graduations, relapses and the
 * Again rate over the selected range, with the equal-length previous window
 * for comparison. Counts, not averages of percentages, so a quiet bucket
 * cannot outweigh a busy one.
 */
@Composable
internal fun ProgressSection(data: ProgressData) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "totals") { TotalsCard(data.totals) }
        item(contentType = "comparison") { ComparisonCard(data.comparison) }
        item(contentType = "flow") { FlowCard(data.trend) }
        item(contentType = "again") { AgainRateCard(data) }
    }
}

@Composable
private fun TotalsCard(totals: ProgressSlice) {
    ChartCard(
        testTag = "stats-progress-totals",
        title = { ChartTitle(R.string.stats_progress_title) },
        subtitle = { ChartSubtitle(R.string.stats_progress_help) }
    ) {
        KpiGrid(
            items = listOf(
                KpiItem(R.string.stats_reviews, totals.reviews.toString()),
                KpiItem(R.string.stats_introduced, totals.introduced.toString()),
                KpiItem(R.string.stats_progress_graduated, totals.graduated.toString()),
                KpiItem(R.string.stats_progress_relapsed, totals.relapsed.toString()),
                KpiItem(R.string.stats_progress_again, pct(totals.againRate)),
                KpiItem(R.string.stats_progress_lapse_rate, pct(totals.lapseRate))
            ),
            columns = 2
        )
    }
}

@Composable
private fun ComparisonCard(comparison: ProgressComparison?) {
    ChartCard(
        testTag = "stats-progress-comparison",
        title = { ChartTitle(R.string.stats_progress_comparison_title) },
        subtitle = { ChartSubtitle(R.string.stats_progress_comparison_help) }
    ) {
        if (comparison == null) {
            ChartEmpty(R.string.stats_progress_no_comparison)
            return@ChartCard
        }
        KpiGrid(
            items = listOf(
                KpiItem(
                    R.string.stats_progress_reviews_vs,
                    "${comparison.currentReviews} (${signedInt(comparison.reviewsDelta)})"
                ),
                KpiItem(
                    R.string.stats_progress_pass_vs,
                    "${pct(comparison.currentPassRate)} " +
                        "(${signedPoints(comparison.passRateDelta)})"
                ),
                KpiItem(
                    R.string.stats_progress_time_vs,
                    "${seconds(comparison.currentAvgSeconds)} " +
                        "(${signedSeconds(comparison.avgSecondsDelta)})"
                ),
                KpiItem(
                    R.string.stats_progress_days_vs,
                    "${comparison.currentStudyDays} " +
                        "(${signedInt(comparison.studyDaysDelta)})"
                )
            ),
            columns = 2
        )
    }
}

@Composable
private fun FlowCard(trend: List<ProgressSlice>) {
    ChartCard(
        testTag = "stats-progress-flow",
        title = { ChartTitle(R.string.stats_progress_flow_title) },
        subtitle = { ChartSubtitle(R.string.stats_progress_flow_help) }
    ) {
        val colors = seriesColors()
        LineChart(
            labels = trend.map { it.label },
            series = listOf(
                LineSeries(trend.map { it.introduced.toDouble() }, colors[0]),
                LineSeries(trend.map { it.graduated.toDouble() }, colors[1]),
                LineSeries(trend.map { it.relapsed.toDouble() }, colors[2])
            ),
            yLabel = { formatCount(it) },
            emptyText = { ChartEmpty(R.string.stats_progress_empty) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendEntry(colors[0], R.string.stats_introduced)
            LegendEntry(colors[1], R.string.stats_progress_graduated)
            LegendEntry(colors[2], R.string.stats_progress_relapsed)
        }
    }
}

@Composable
private fun AgainRateCard(data: ProgressData) {
    ChartCard(
        testTag = "stats-progress-again",
        title = { ChartTitle(R.string.stats_progress_again_title) },
        subtitle = { ChartSubtitle(R.string.stats_progress_again_help) }
    ) {
        LineChart(
            labels = data.trend.map { it.label },
            series = listOf(
                LineSeries(
                    data.trend.map { it.againRate },
                    color = MaterialTheme.colorScheme.error,
                    fill = true
                )
            ),
            fixedMax = 1.0,
            yLabel = { formatPct(it) },
            emptyText = { ChartEmpty(R.string.stats_progress_empty) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.stats_progress_again_value,
                pct(data.totals.againRate),
                pct(data.totals.lapseRate)
            ),
            style = MaterialTheme.typography.bodySmall
        )
        val comparison = data.comparison
        if (comparison != null && comparison.passRateDelta != null) {
            Text(
                stringResource(
                    R.string.stats_progress_pass_delta,
                    signedPoints(comparison.passRateDelta)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

private fun pct(value: Double?): String =
    value?.let { formatPct(it) } ?: "—"

private fun seconds(value: Double?): String =
    value?.let { formatSeconds(it) } ?: "—"

private fun signedInt(value: Int): String =
    if (value >= 0) "+$value" else value.toString()

private fun signedPoints(value: Double?): String {
    val v = value ?: return "—"
    return "${if (v >= 0) "+" else "-"}${abs(v).roundToInt()} pts"
}

private fun signedSeconds(value: Double?): String {
    val v = value ?: return "—"
    return "${if (v >= 0) "+" else "-"}${formatSeconds(abs(v))}"
}
