package com.mcqapp.ui.stats.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.StackedPoint
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.StackedBarChart
import com.mcqapp.ui.stats.charts.formatPct

/**
 * The Sessions tab. It charts only the sitting-level views the Workload tab
 * does not already show: how many sittings started each day, how long they ran,
 * when they began, and how recall moved between sittings. The Workload tab's
 * session summary (count, means, reviews-per-sitting histogram) stays there.
 */
@Composable
internal fun SessionsSection(bundle: SessionsData) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (bundle.totalSessions == 0) {
            item(contentType = "empty") {
                ChartCard(
                    testTag = "stats-sessions-empty",
                    title = { ChartTitle(R.string.tab_sessions) },
                    subtitle = { ChartSubtitle(R.string.stats_sessions_help) }
                ) {
                    ChartEmpty()
                }
            }
            return@LazyColumn
        }
        item(contentType = "per-day") { SessionsPerDayCard(bundle) }
        item(contentType = "length") { SessionLengthCard(bundle) }
        item(contentType = "start") { SessionStartCard(bundle) }
        item(contentType = "weekday") { SessionWeekdayCard(bundle) }
        item(contentType = "accuracy") { SessionAccuracyCard(bundle) }
    }
}

@Composable
private fun SessionsPerDayCard(bundle: SessionsData) {
    ChartCard(
        testTag = "stats-sessions-per-day",
        title = { ChartTitle(R.string.stats_sessions_per_day) },
        subtitle = { ChartSubtitle(R.string.stats_sessions_per_day_help) }
    ) {
        StackedBarChart(points = bundle.sessionsPerDay, segmentCount = 1)
    }
}

@Composable
private fun SessionLengthCard(bundle: SessionsData) {
    ChartCard(
        testTag = "stats-session-length",
        title = { ChartTitle(R.string.stats_session_length) },
        subtitle = { ChartSubtitle(R.string.stats_session_length_help) }
    ) {
        StackedBarChart(
            points = bundle.durationHistogram.map { point ->
                StackedPoint(
                    label = point.label,
                    segments = listOf(point.value ?: 0.0),
                    total = point.count
                )
            },
            segmentCount = 1
        )
    }
}

@Composable
private fun SessionStartCard(bundle: SessionsData) {
    ChartCard(
        testTag = "stats-session-start",
        title = { ChartTitle(R.string.stats_session_start) },
        subtitle = { ChartSubtitle(R.string.stats_session_start_help) }
    ) {
        StackedBarChart(
            points = bundle.startHours.map { point ->
                StackedPoint(point.hour.toString(), listOf(point.sessions.toDouble()), point.sessions)
            },
            segmentCount = 1
        )
    }
}

@Composable
private fun SessionWeekdayCard(bundle: SessionsData) {
    val labels = listOf(
        R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed,
        R.string.weekday_thu, R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun
    )
    ChartCard(
        testTag = "stats-sessions-weekday",
        title = { ChartTitle(R.string.stats_sessions_weekday) },
        subtitle = { ChartSubtitle(R.string.stats_sessions_weekday_help) }
    ) {
        StackedBarChart(
            points = bundle.weekdays.map { point ->
                StackedPoint(
                    label = stringResource(labels[point.weekday.coerceIn(0, 6)]),
                    segments = listOf(point.sessions.toDouble()),
                    total = point.sessions
                )
            },
            segmentCount = 1
        )
    }
}

@Composable
private fun SessionAccuracyCard(bundle: SessionsData) {
    ChartCard(
        testTag = "stats-session-accuracy",
        title = { ChartTitle(R.string.stats_session_accuracy) },
        subtitle = { ChartSubtitle(R.string.stats_session_accuracy_help) }
    ) {
        LineChart(
            labels = bundle.accuracyTrend.map { it.label },
            series = listOf(
                LineSeries(
                    values = bundle.accuracyTrend.map { it.value },
                    color = MaterialTheme.colorScheme.primary,
                    fill = true
                )
            ),
            fixedMax = 1.0,
            yLabel = { formatPct(it) },
            emptyText = { ChartEmpty(R.string.stats_no_data) }
        )
    }
}
