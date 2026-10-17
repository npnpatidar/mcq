package com.mcqapp.ui.stats.sections

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.MemoryData
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.StackedBarChart
import com.mcqapp.ui.stats.charts.formatCount
import com.mcqapp.ui.stats.charts.formatPct
import java.util.Locale

/**
 * Tab 4: the FSRS memory model's shape — stability, difficulty and
 * retrievability distributions, the interval spread, and how stability grew
 * with each review.
 */
@Composable
internal fun MemorySection(bundle: MemoryData) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "distribution") { DistributionCard(bundle.stability, R.string.stats_stability, R.string.stats_stability_help) }
        item(contentType = "distribution") { DistributionCard(bundle.difficulty, R.string.stats_difficulty, R.string.stats_difficulty_help) }
        item(contentType = "retrievability") { RetrievabilityCard(bundle) }
        item(contentType = "interval") { IntervalCard(bundle) }
        item(contentType = "growth") { GrowthCard(bundle) }
    }
}

@Composable
private fun DistributionCard(
    distribution: com.mcqapp.domain.stats.Distribution,
    @androidx.annotation.StringRes titleRes: Int,
    @androidx.annotation.StringRes helpRes: Int
) {
    ChartCard(
        testTag = "stats-dist-$titleRes",
        title = { ChartTitle(titleRes) },
        subtitle = { ChartSubtitle(helpRes) }
    ) {
        if (distribution.bins.all { it.count == 0 }) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        StackedBarChart(
            points = distribution.bins.map { bin ->
                com.mcqapp.domain.stats.StackedPoint(
                    label = bin.label,
                    segments = listOf(bin.count.toDouble()),
                    total = bin.count
                )
            },
            segmentCount = 1
        )
        SummaryLine(distribution)
    }
}

@Composable
private fun RetrievabilityCard(bundle: MemoryData) {
    ChartCard(
        testTag = "stats-retrievability",
        title = { ChartTitle(R.string.stats_retrievability) },
        subtitle = { ChartSubtitle(R.string.stats_retrievability_help) }
    ) {
        if (bundle.retrievability.bins.all { it.count == 0 }) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        StackedBarChart(
            points = bundle.retrievability.bins.map { bin ->
                com.mcqapp.domain.stats.StackedPoint(
                    label = bin.label,
                    segments = listOf(bin.count.toDouble()),
                    total = bin.count
                )
            },
            segmentCount = 1
        )
        SummaryLine(bundle.retrievability)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.stats_estimated_knowledge_value, bundle.kpis.estimatedKnowledge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IntervalCard(bundle: MemoryData) {
    ChartCard(
        testTag = "stats-intervals",
        title = { ChartTitle(R.string.stats_intervals) },
        subtitle = { ChartSubtitle(R.string.stats_intervals_help) }
    ) {
        if (bundle.intervals.bins.all { it.count == 0 }) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        val total = bundle.intervals.bins.sumOf { it.count }
        val cumul = mutableListOf<Double>()
        var running = 0.0
        bundle.intervals.bins.forEach { bin ->
            running += bin.count.toDouble()
            cumul.add(if (total == 0) 0.0 else running / total)
        }
        StackedBarChart(
            points = bundle.intervals.bins.map { bin ->
                com.mcqapp.domain.stats.StackedPoint(
                    label = bin.label,
                    segments = listOf(bin.count.toDouble()),
                    total = bin.count
                )
            },
            segmentCount = 1,
            overlay = cumul,
            overlayMax = 1.0,
            overlayLabel = { formatPct(it) }
        )
        SummaryLine(bundle.intervals)
    }
}

@Composable
private fun SummaryLine(distribution: com.mcqapp.domain.stats.Distribution) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(
                R.string.stats_mean_value,
                distribution.mean?.let { oneDecimal(it) } ?: "—"
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(
                R.string.stats_median_value,
                distribution.median?.let { oneDecimal(it) } ?: "—"
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun oneDecimal(value: Double): String =
    String.format(Locale.ENGLISH, "%.1f", value)

@Composable
private fun GrowthCard(bundle: MemoryData) {
    ChartCard(
        testTag = "stats-memory-growth",
        title = { ChartTitle(R.string.stats_memory_growth) },
        subtitle = { ChartSubtitle(R.string.stats_memory_growth_help) }
    ) {
        if (bundle.memoryGrowth.byReviewNumber.all { it.value == null }) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        Text(
            stringResource(R.string.stats_by_review_number),
            style = MaterialTheme.typography.labelMedium
        )
        LineChart(
            labels = bundle.memoryGrowth.byReviewNumber.map { it.label },
            series = listOf(
                LineSeries(
                    values = bundle.memoryGrowth.byReviewNumber.map { it.value },
                    color = MaterialTheme.colorScheme.primary,
                    fill = true
                )
            ),
            yLabel = { formatCount(it) },
            emptyText = { ChartEmpty(R.string.stats_no_data) }
        )
        if (bundle.memoryGrowth.overTime.size > 1) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.stats_over_time),
                style = MaterialTheme.typography.labelMedium
            )
            LineChart(
                labels = bundle.memoryGrowth.overTime.map { it.label },
                series = listOf(
                    LineSeries(
                        values = bundle.memoryGrowth.overTime.map { it.value },
                        color = MaterialTheme.colorScheme.tertiary
                    )
                ),
                yLabel = { formatCount(it) },
                emptyText = { ChartEmpty(R.string.stats_no_data) }
            )
        }
    }
}
