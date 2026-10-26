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
import com.mcqapp.domain.stats.CardsData
import com.mcqapp.domain.stats.CardCountsData
import com.mcqapp.domain.stats.OverdueAgeData
import com.mcqapp.domain.stats.PaperCardCounts
import com.mcqapp.domain.stats.StackedPoint
import com.mcqapp.domain.stats.WeakCard
import com.mcqapp.ui.stats.WeakCardRow
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.DonutChart
import com.mcqapp.ui.stats.charts.LegendDot
import com.mcqapp.ui.stats.charts.MeterBar
import com.mcqapp.ui.stats.charts.StackedBarChart
import com.mcqapp.ui.stats.charts.seriesColors
import java.util.Locale

/**
 * Tab 5: the collection's current shape — the state donut with its legend and
 * the flagged leeches.
 */
/** Rows shown in the per-paper counts table before it is capped (E9). */
private const val PAPER_TABLE_CAP = 50

@Composable
internal fun CardsSection(
    bundle: CardsData,
    questionText: Map<String, String>,
    onOpenCard: (WeakCard) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "card-counts") { CardCountsCard(bundle.cardCounts) }
        item(contentType = "overdue-age") { OverdueAgeCard(bundle.overdueAge) }
        if (bundle.paperCounts.isNotEmpty()) {
            item(contentType = "paper-counts") { PaperCountsCard(bundle.paperCounts) }
        }
        if (bundle.leeches.isNotEmpty()) {
            item(contentType = "leeches") { LeechesCard(bundle, questionText, onOpenCard) }
        }
    }
}

@Composable
private fun CardCountsCard(counts: CardCountsData) {
    val colors = seriesColors()
    ChartCard(
        testTag = "stats-card-counts",
        title = { ChartTitle(R.string.stats_card_counts) }
    ) {
        if (counts.total == 0) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                slices = listOf(
                    counts.fresh.toDouble() to colors[0],
                    counts.learning.toDouble() to colors[1],
                    counts.relearning.toDouble() to colors[2],
                    counts.young.toDouble() to colors[3],
                    counts.mature.toDouble() to colors[4]
                ),
                center = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            counts.total.toString(),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.stats_total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
            Spacer(Modifier.height(0.dp))
            Column(
                modifier = Modifier.padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CountLegendRow(colors[0], R.string.stats_new_cards, counts.fresh)
                CountLegendRow(colors[1], R.string.stats_learning, counts.learning)
                CountLegendRow(colors[2], R.string.stats_relearning, counts.relearning)
                CountLegendRow(colors[3], R.string.stats_young, counts.young)
                CountLegendRow(colors[4], R.string.stats_mature, counts.mature)
            }
        }
        if (counts.leeches > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.stats_leeches_count, counts.leeches),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun CountLegendRow(color: androidx.compose.ui.graphics.Color, @androidx.annotation.StringRes labelRes: Int, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(color)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp)
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun OverdueAgeCard(data: OverdueAgeData) {
    ChartCard(
        testTag = "stats-overdue-age",
        title = { ChartTitle(R.string.stats_overdue_age) },
        subtitle = { ChartSubtitle(R.string.stats_overdue_age_help) }
    ) {
        if (data.overdue == 0) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        StackedBarChart(
            points = data.bins.map { bin ->
                StackedPoint(bin.label, listOf(bin.count.toDouble()), bin.count)
            },
            segmentCount = 1
        )
        SummaryLine(data)
    }
}

@Composable
private fun SummaryLine(data: OverdueAgeData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(
                R.string.stats_overdue_mean,
                data.meanAgeDays?.let { oneDecimal(it) } ?: "—"
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.stats_overdue_oldest, data.oldestAgeDays ?: 0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun oneDecimal(value: Double): String =
    String.format(Locale.ENGLISH, "%.1f", value)

@Composable
private fun PaperCountsCard(counts: List<PaperCardCounts>) {
    ChartCard(
        testTag = "stats-paper-counts",
        title = { ChartTitle(R.string.stats_paper_card_counts) },
        subtitle = { ChartSubtitle(R.string.stats_paper_card_counts_help) }
    ) {
        if (counts.isEmpty()) {
            ChartEmpty(R.string.stats_no_data)
            return@ChartCard
        }
        val maxTotal = counts.maxOf { it.total }.coerceAtLeast(1)
        // One LazyColumn item composing every row never recycled: at the
        // documented 1000-paper ceiling that is 1000 rows in one frame (the
        // other ranked lists cap at 15) (consolidated_bugs.md E9).
        counts.take(PAPER_TABLE_CAP).forEach { paper ->
            PaperCountRow(paper, maxTotal)
        }
        if (counts.size > PAPER_TABLE_CAP) {
            Text(
                stringResource(R.string.stats_paper_table_capped, counts.size - PAPER_TABLE_CAP),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PaperCountRow(paper: PaperCardCounts, maxTotal: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                paper.label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                paper.total.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        MeterBar(
            fraction = paper.total.toDouble() / maxTotal.toDouble(),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun LeechesCard(
    bundle: CardsData,
    questionText: Map<String, String>,
    onOpenCard: (WeakCard) -> Unit
) {
    ChartCard(
        testTag = "stats-leeches",
        title = { ChartTitle(R.string.stats_leeches) },
        subtitle = { ChartSubtitle(R.string.stats_leeches_help) }
    ) {
        bundle.leeches.forEach { card ->
            WeakCardRow(
                card = card,
                questionText = questionText[card.questionId],
                onClick = { onOpenCard(card) }
            )
        }
    }
}
