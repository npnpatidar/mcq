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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.stats.AnswerButtonsData
import com.mcqapp.domain.stats.StackedPoint
import com.mcqapp.domain.stats.StatsBundle
import com.mcqapp.domain.stats.WeakCard
import com.mcqapp.ui.stats.WeakCardRow
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.LegendDot
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.StackedBarChart
import com.mcqapp.ui.stats.charts.formatDuration
import com.mcqapp.ui.stats.charts.formatPct
import com.mcqapp.ui.stats.charts.seriesColors
import kotlin.math.roundToInt

/**
 * Tab 3: the future (forecast, daily load) and the past (review counts, time,
 * answer buttons, hourly breakdown, response-time analysis).
 */
@Composable
internal fun WorkloadSection(
    bundle: StatsBundle,
    questionText: Map<String, String>,
    onOpenCard: (WeakCard) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { ForecastCard(bundle) }
        item { ReviewsPerDayCard(bundle) }
        item { TimePerDayCard(bundle) }
        item { AnswerButtonsCard(bundle) }
        item { HourlyCard(bundle) }
        item { ResponseTimeCard(bundle, questionText, onOpenCard) }
    }
}

@Composable
private fun ForecastCard(bundle: StatsBundle) {
    val forecast = bundle.forecast
    ChartCard(
        testTag = "stats-forecast",
        title = { ChartTitle(R.string.stats_forecast) },
        subtitle = { ChartSubtitle(R.string.stats_forecast_help) }
    ) {
        StackedBarChart(
            points = forecast.days.map { day ->
                com.mcqapp.domain.stats.StackedPoint(
                    label = day.label,
                    segments = listOf(day.learning.toDouble(), day.review.toDouble()),
                    total = day.learning + day.review
                )
            },
            segmentCount = 2,
            overlay = forecast.cumulativeIfSkipped.map { it.toDouble() },
            overlayLabel = { it.roundToInt().toString() }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendEntry(seriesColors()[0], R.string.stats_learning)
            LegendEntry(seriesColors()[1], R.string.stats_review_kind)
            LegendEntry(
                MaterialTheme.colorScheme.onSurfaceVariant,
                R.string.stats_cumulative_if_skipped
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.stats_overdue_value, forecast.overdue),
                style = MaterialTheme.typography.labelSmall,
                color = if (forecast.overdue > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.stats_due_today_value, forecast.dueToday),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(
                    R.string.stats_daily_load_value,
                    String.format(java.util.Locale.ENGLISH, "%.1f", forecast.dailyLoad)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReviewsPerDayCard(bundle: StatsBundle) {
    ChartCard(
        testTag = "stats-reviews-per-day",
        title = { ChartTitle(R.string.stats_reviews_per_day) }
    ) {
        val colors = seriesColors()
        StackedBarChart(
            points = bundle.reviewsPerDay,
            emptyText = { ChartEmpty(R.string.stats_no_data) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendEntry(colors[0], R.string.stats_learning)
            LegendEntry(colors[1], R.string.stats_review_kind)
            LegendEntry(colors[2], R.string.stats_relearning)
            LegendEntry(colors[3], R.string.stats_unknown_kind)
        }
    }
}

@Composable
private fun TimePerDayCard(bundle: StatsBundle) {
    ChartCard(
        testTag = "stats-time-per-day",
        title = { ChartTitle(R.string.stats_time_per_day) }
    ) {
        StackedBarChart(
            points = bundle.timePerDay.map { point ->
                com.mcqapp.domain.stats.StackedPoint(
                    label = point.label,
                    segments = listOf(point.value ?: 0.0),
                    total = point.count
                )
            },
            segmentCount = 1,
            yLabel = { formatDuration(it) },
            emptyText = { ChartEmpty(R.string.stats_no_data) }
        )
    }
}

@Composable
private fun AnswerButtonsCard(bundle: StatsBundle) {
    ChartCard(
        testTag = "stats-answer-buttons",
        title = { ChartTitle(R.string.stats_answer_buttons) },
        subtitle = { ChartSubtitle(R.string.stats_answer_buttons_help) }
    ) {
        if (bundle.answerButtons.groups.isEmpty()) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text(
                "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall
            )
            AnswerHeader(R.string.grade_again)
            AnswerHeader(R.string.grade_hard)
            AnswerHeader(R.string.grade_good)
            AnswerHeader(R.string.grade_easy)
            AnswerHeader(R.string.stats_pass_rate)
        }
        bundle.answerButtons.groups.forEach { group ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    answerGroupLabel(group.kind),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                AnswerCell(group.again, MaterialTheme.colorScheme.error)
                AnswerCell(group.hard, null)
                AnswerCell(group.good, null)
                AnswerCell(group.easy, null)
                Text(
                    group.passRate?.let { formatPct(it) } ?: "—",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AnswerHeader(@StringRes labelRes: Int) {
    Text(
        stringResource(labelRes),
        modifier = Modifier.padding(start = 10.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AnswerCell(count: Int, color: androidx.compose.ui.graphics.Color?) {
    Text(
        count.toString(),
        modifier = Modifier.padding(start = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color ?: MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun answerGroupLabel(kind: AnswerButtonsData.Kind): String = when (kind) {
    AnswerButtonsData.Kind.LEARNING -> stringResource(R.string.stats_learning)
    AnswerButtonsData.Kind.YOUNG -> stringResource(R.string.stats_young)
    AnswerButtonsData.Kind.MATURE -> stringResource(R.string.stats_mature)
    AnswerButtonsData.Kind.RELEARNING -> stringResource(R.string.stats_relearning)
}

@Composable
private fun HourlyCard(bundle: StatsBundle) {
    ChartCard(
        testTag = "stats-hourly",
        title = { ChartTitle(R.string.stats_hourly) },
        subtitle = { ChartSubtitle(R.string.stats_hourly_help) }
    ) {
        StackedBarChart(
            points = bundle.hourly.map { hour ->
                com.mcqapp.domain.stats.StackedPoint(
                    label = "${hour.hour}",
                    segments = listOf(hour.reviews.toDouble()),
                    total = hour.reviews
                )
            },
            segmentCount = 1,
            overlay = bundle.hourly.map { it.passRate },
            emptyText = { ChartEmpty(R.string.stats_no_data) }
        )
    }
}

@Composable
private fun ResponseTimeCard(
    bundle: StatsBundle,
    questionText: Map<String, String>,
    onOpenCard: (WeakCard) -> Unit
) {
    val response = bundle.responseTime
    ChartCard(
        testTag = "stats-response-time",
        title = { ChartTitle(R.string.stats_response_time) }
    ) {
        if (response.histogram.all { (it.value ?: 0.0) == 0.0 }) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        Text(
            stringResource(R.string.stats_answer_speed),
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(4.dp))
        StackedBarChart(
            points = response.histogram.map { point ->
                StackedPoint(point.label, listOf(point.value ?: 0.0), point.count)
            },
            segmentCount = 1
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.stats_time_by_grade),
            style = MaterialTheme.typography.labelMedium
        )
        response.avgByGrade.forEach { grade ->
            if (grade.avgSeconds != null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        gradeLabel(grade.grade),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${grade.avgSeconds!!.roundToInt()}s · ${grade.reviews}×",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (response.fastFails > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.stats_fast_fails, response.fastFails),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (response.trend.any { it.value != null }) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.stats_time_trend),
                style = MaterialTheme.typography.labelMedium
            )
            LineChart(
                labels = response.trend.map { it.label },
                series = listOf(
                    LineSeries(
                        values = response.trend.map { it.value },
                        color = MaterialTheme.colorScheme.primary
                    )
                ),
                yLabel = { formatDuration(it) },
                emptyText = { ChartEmpty(R.string.stats_no_data) }
            )
        }
        if (response.slowest.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.stats_slowest),
                style = MaterialTheme.typography.labelMedium
            )
            response.slowest.forEach { card ->
                WeakCardRow(
                    card = card,
                    questionText = questionText[card.questionId],
                    onClick = { onOpenCard(card) }
                )
            }
        }
    }
}

@Composable
private fun gradeLabel(grade: ReviewGrade): String = when (grade) {
    ReviewGrade.AGAIN -> stringResource(R.string.grade_again)
    ReviewGrade.HARD -> stringResource(R.string.grade_hard)
    ReviewGrade.GOOD -> stringResource(R.string.grade_good)
    ReviewGrade.EASY -> stringResource(R.string.grade_easy)
}

@Composable
private fun LegendEntry(color: androidx.compose.ui.graphics.Color, @StringRes labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(color)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
