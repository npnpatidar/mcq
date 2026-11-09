package com.mcqapp.ui.stats.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.TestProgressData
import com.mcqapp.domain.TestType
import com.mcqapp.domain.stats.ExamGroup
import com.mcqapp.domain.stats.ExamStats
import com.mcqapp.ui.common.familyLabel
import com.mcqapp.ui.common.testTypeLabel
import com.mcqapp.ui.stats.KpiGrid
import com.mcqapp.ui.stats.KpiItem
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.MeterBar
import com.mcqapp.ui.stats.charts.formatDuration
import com.mcqapp.ui.stats.charts.formatPct
import com.mcqapp.ui.stats.charts.formatSeconds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Exam sections of the Tests tab (plan §3.3): one comparable group at a
 * time, its trend, per-paper and category figures, and the recent attempts.
 * Strict mocks, Fresh samples and practice are never averaged together, and a
 * short or legacy attempt appears only in the recent list with its badge.
 */
@Composable
internal fun ExamSection(data: TestProgressData, onOpenAttempt: (Long) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(defaultGroup(data)) }
    val exam = data.exams[selected]

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_exam_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.stats_exam_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExamGroup.entries.forEach { group ->
                    FilterChip(
                        selected = selected == group,
                        onClick = { selected = group },
                        label = { Text(stringResource(groupLabel(group))) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (exam == null || exam.familyAttempts == 0) {
                Text(
                    stringResource(R.string.stats_exam_empty),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                ExamBody(exam)
            }
        }
    }

    val recent = exam?.recent ?: data.exams.values.firstOrNull()?.recent.orEmpty()
    if (recent.isNotEmpty()) {
        RecentCard(recent, onOpenAttempt)
    }
}

@Composable
private fun ExamBody(exam: ExamStats) {
    KeyLine(exam)
    Spacer(Modifier.height(8.dp))
    KpiGrid(
        items = listOf(
            KpiItem(R.string.stats_exam_kpi_latest, percent(exam.latestPercent)),
            KpiItem(R.string.stats_exam_kpi_best, percent(exam.bestPercent)),
            KpiItem(R.string.stats_exam_kpi_average, percent(exam.averagePercent)),
            KpiItem(R.string.stats_exam_kpi_delta, delta(exam.deltaPoints)),
            KpiItem(R.string.stats_exam_kpi_attempts, exam.attempts.toString()),
            KpiItem(R.string.stats_exam_kpi_time, formatDuration(exam.totalMinutes * 60.0)),
            KpiItem(R.string.stats_exam_kpi_skip, percentOf(exam.skipRate)),
            KpiItem(
                R.string.stats_exam_kpi_seconds,
                exam.averageSecondsPerQuestion?.let { formatSeconds(it) } ?: "—"
            )
        ),
        columns = 2
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(
            if (exam.lifetimeIsRepresentative) {
                R.string.stats_exam_source_mock
            } else {
                R.string.stats_exam_source_personal
            },
            if (exam.lifetime.hasData) formatPct(exam.lifetime.accuracy) else "—"
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    TrendBlock(exam)
    if (exam.papers.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        PaperRows(exam)
    }
    if (exam.categories.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        CategoryRows(exam)
    }
    Spacer(Modifier.height(8.dp))
    TimingFootnote(exam)
}

@Composable
private fun KeyLine(exam: ExamStats) {
    val key = exam.comparisonKey ?: return
    Text(
        stringResource(
            R.string.stats_exam_key,
            exam.paperTitle ?: "",
            testTypeLabel(TestType.fromStorage(key.testType)),
            key.requestedCount
        ),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium
    )
    Text(
        if (key.isWholePaper) {
            stringResource(R.string.stats_exam_scope_whole)
        } else {
            stringResource(R.string.stats_exam_scope_custom)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        stringResource(R.string.stats_exam_sample, exam.attempts, exam.shown),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TrendBlock(exam: ExamStats) {
    val labels = exam.trend.map { it.label }
    val values = exam.trend.map { it.percent?.div(100.0) }
    LineChart(
        labels = labels,
        series = listOf(
            LineSeries(
                values = values,
                color = MaterialTheme.colorScheme.primary,
                fill = true
            )
        ),
        fixedMax = 1.0,
        yLabel = { formatPct(it) },
        emptyText = { ChartEmpty(R.string.stats_exam_trend_empty) }
    )
    val margin = exam.marginPoints
    if (margin != null) {
        Text(
            stringResource(R.string.stats_exam_margin, margin, exam.shown),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (exam.deltaIsNoise) {
        Text(
            stringResource(R.string.stats_exam_noise),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PaperRows(exam: ExamStats) {
    Text(
        stringResource(R.string.stats_exam_papers_title),
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        stringResource(R.string.stats_exam_papers_help),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    exam.papers.forEach { row ->
        Text(
            stringResource(
                R.string.stats_exam_paper_row,
                row.title,
                row.attempts,
                percent(row.latestPercent),
                percent(row.bestPercent),
                delta(row.deltaPoints)
            ),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CategoryRows(exam: ExamStats) {
    Text(
        stringResource(R.string.stats_exam_categories_title),
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        stringResource(R.string.stats_exam_categories_help),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    exam.categories.forEach { row ->
        Column(modifier = Modifier.padding(vertical = 3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    row.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.stats_exam_category_row,
                        row.correct,
                        row.asked,
                        (row.accuracy * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MeterBar(
                fraction = row.accuracy,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
    if (exam.unresolvedCategories > 0) {
        Text(
            stringResource(R.string.stats_exam_unresolved, exam.unresolvedCategories),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimingFootnote(exam: ExamStats) {
    Text(
        stringResource(R.string.stats_exam_timing_title),
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        stringResource(
            R.string.stats_exam_timing_line,
            exam.shown,
            exam.correct,
            exam.wrong,
            exam.skipped,
            exam.totalMinutes
        ),
        style = MaterialTheme.typography.bodySmall
    )
    exam.averageSecondsPerQuestion?.let {
        Text(
            stringResource(R.string.stats_exam_seconds, it, exam.attempts),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    exam.skipRate?.let {
        Text(
            stringResource(R.string.stats_exam_skip_rate, (it * 100).roundToInt()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecentCard(
    recent: List<com.mcqapp.domain.stats.ExamRecentAttempt>,
    onOpenAttempt: (Long) -> Unit
) {
    val format = remember { SimpleDateFormat("dd MMM yy", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_exam_recent_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.stats_exam_recent_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            recent.forEach { attempt ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAttempt(attempt.attemptId) }
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        attempt.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(
                            R.string.stats_exam_recent_row,
                            familyLabel(attempt.family),
                            attempt.correct,
                            attempt.shown,
                            attempt.percent.roundToInt(),
                            format.format(Date(attempt.finishedAt))
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun defaultGroup(data: TestProgressData): ExamGroup =
    ExamGroup.entries.firstOrNull { (data.exams[it]?.familyAttempts ?: 0) > 0 }
        ?: ExamGroup.MOCK_STRICT

private fun groupLabel(group: ExamGroup): Int = when (group) {
    ExamGroup.MOCK_STRICT -> R.string.stats_exam_group_strict
    ExamGroup.MOCK_FRESH -> R.string.stats_exam_group_fresh
    ExamGroup.PRACTICE -> R.string.stats_exam_group_practice
}

private fun percent(value: Double?): String =
    value?.let { "${it.roundToInt()}%" } ?: "—"

private fun percentOf(value: Double?): String =
    value?.let { "${(it * 100).roundToInt()}%" } ?: "—"

private fun delta(value: Double?): String {
    val v = value ?: return "—"
    val sign = if (v >= 0) "+" else "-"
    return "$sign${abs(v).roundToInt()}"
}
