package com.mcqapp.ui.stats.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.OverviewData
import com.mcqapp.domain.stats.WeakCard
import com.mcqapp.ui.stats.RetentionTableHeader
import com.mcqapp.ui.stats.RetentionTableRow
import com.mcqapp.ui.stats.KpiGrid
import com.mcqapp.ui.stats.KpiItem
import com.mcqapp.ui.stats.WeakCardRow
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.HeatmapChart
import com.mcqapp.ui.stats.charts.formatPct
import kotlin.math.roundToInt

/**
 * Tab 1: the today strip, headline KPIs, the true-retention table, the
 * calendar with streaks, the most-forgotten cards and the scheduler status.
 */
@Composable
internal fun OverviewSection(
    bundle: OverviewData,
    questionText: Map<String, String>,
    onOpenCard: (WeakCard) -> Unit,
    onOpenSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { TodayCard(bundle) }
        item { KpisCard(bundle) }
        item { TrueRetentionCard(bundle) }
        item { CalendarCard(bundle) }
        if (bundle.weakest.isNotEmpty()) {
            item { WeakestCard(bundle, questionText, onOpenCard) }
        }
        item { SchedulerCard(bundle, onOpenSettings) }
    }
}

@Composable
private fun TodayCard(bundle: OverviewData) {
    val today = bundle.today
    ChartCard(testTag = "stats-today", title = { ChartTitle(R.string.period_today) }) {
        KpiGrid(
            listOf(
                KpiItem(R.string.stats_reviews, today.reviews.toString()),
                KpiItem(
                    R.string.stats_correct,
                    today.correctPct?.let { formatPct(it) } ?: "—"
                ),
                KpiItem(
                    R.string.stats_time_studied,
                    com.mcqapp.ui.stats.charts.formatDuration(today.timeSeconds.toDouble())
                ),
                KpiItem(R.string.stats_introduced, today.introduced.toString()),
                KpiItem(R.string.stats_due_now, today.dueNow.toString()),
                KpiItem(
                    R.string.stats_backlog,
                    today.backlog.toString(),
                    if (today.backlog > 0) MaterialTheme.colorScheme.error else null
                )
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.stats_today_kinds, today.learning, today.review, today.relearning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KpisCard(bundle: OverviewData) {
    val kpis = bundle.kpis
    ChartCard(testTag = "stats-kpis", title = { ChartTitle(R.string.stats_overview) }) {
        KpiGrid(
            listOf(
                KpiItem(
                    R.string.stats_retention,
                    kpis.retention?.let { formatPct(it) } ?: "—"
                ),
                KpiItem(R.string.stats_desired_retention, formatPct(kpis.desiredRetention)),
                KpiItem(
                    R.string.stats_mature_retention,
                    kpis.matureRetention?.let { formatPct(it) } ?: "—"
                ),
                KpiItem(
                    R.string.stats_streak_current_days,
                    kpis.currentStreak.toString()
                ),
                KpiItem(R.string.stats_reviews_total, kpis.reviews.toString()),
                KpiItem(R.string.stats_days_studied, kpis.days.toString()),
                KpiItem(
                    R.string.stats_time_studied,
                    com.mcqapp.ui.stats.charts.formatDuration(kpis.timeSeconds.toDouble())
                ),
                KpiItem(
                    R.string.stats_avg_answer,
                    kpis.avgAnswerSeconds?.let { "${it.roundToInt()}s" } ?: "—"
                ),
                KpiItem(R.string.stats_estimated_knowledge, kpis.estimatedKnowledge.toString()),
                KpiItem(
                    R.string.stats_avg_interval,
                    kpis.avgIntervalDays?.let { String.format(java.util.Locale.ENGLISH, "%.1f d", it) } ?: "—"
                ),
                KpiItem(
                    R.string.stats_leeches,
                    kpis.leeches.toString(),
                    if (kpis.leeches > 0) MaterialTheme.colorScheme.error else null
                ),
                KpiItem(
                    R.string.stats_streak_longest_days,
                    kpis.longestStreak.toString()
                )
            )
        )
    }
}

@Composable
private fun TrueRetentionCard(bundle: OverviewData) {
    ChartCard(
        testTag = "stats-retention-table",
        title = { ChartTitle(R.string.stats_true_retention) },
        subtitle = { ChartSubtitle(R.string.stats_true_retention_help) }
    ) {
        RetentionTableHeader()
        bundle.retentionTable.forEach { row ->
            RetentionTableRow(
                label = periodLabel(row.label),
                all = row.allRetention to row.allReviews,
                young = row.youngRetention to row.youngReviews,
                mature = row.matureRetention to row.matureReviews
            )
        }
    }
}

@Composable
private fun periodLabel(label: String): String = when (label) {
    "today" -> stringResource(R.string.period_today)
    "yesterday" -> stringResource(R.string.period_yesterday)
    "last7" -> stringResource(R.string.period_last_7_days)
    "last30" -> stringResource(R.string.period_last_30_days)
    "last90" -> stringResource(R.string.period_last_90_days)
    "last365" -> stringResource(R.string.period_last_365_days)
    else -> stringResource(R.string.period_all_time)
}

@Composable
private fun CalendarCard(bundle: OverviewData) {
    ChartCard(
        testTag = "stats-heatmap",
        title = { ChartTitle(R.string.stats_calendar) }
    ) {
        // Resolved through the context, not stringResource: the tooltip
        // formatter runs inside the canvas draw scope.
        val resources = LocalContext.current.resources
        HeatmapChart(
            days = bundle.heatmap.days,
            tooltipCount = { resources.getString(R.string.stats_n_reviews, it) }
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                stringResource(R.string.stats_streak_current_value, bundle.heatmap.currentStreak),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.stats_streak_longest_value, bundle.heatmap.longestStreak),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeakestCard(
    bundle: OverviewData,
    questionText: Map<String, String>,
    onOpenCard: (WeakCard) -> Unit
) {
    ChartCard(
        testTag = "stats-weakest",
        title = { ChartTitle(R.string.stats_weakest_cards) },
        subtitle = { ChartSubtitle(R.string.stats_weakest_help) }
    ) {
        bundle.weakest.forEach { card ->
            WeakCardRow(
                card = card,
                questionText = questionText[card.questionId],
                onClick = { onOpenCard(card) }
            )
        }
    }
}

@Composable
private fun SchedulerCard(bundle: OverviewData, onOpenSettings: () -> Unit) {
    val status = bundle.fsrsStatus
    ChartCard(
        testTag = "stats-scheduler",
        title = { ChartTitle(R.string.stats_scheduler) }
    ) {
        Text(
            if (status.fitted) {
                stringResource(R.string.stats_fsrs_fitted)
            } else {
                stringResource(R.string.stats_fsrs_default)
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.stats_desired_retention_value, formatPct(status.desiredRetention)),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(4.dp))
        if (!status.fitted && status.reviewCount < status.unlockReviews) {
            Text(
                stringResource(R.string.stats_fsrs_unlock, status.reviewCount, status.unlockReviews),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = {
                    (status.reviewCount.toFloat() / status.unlockReviews).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.stats_open_settings))
        }
    }
}
