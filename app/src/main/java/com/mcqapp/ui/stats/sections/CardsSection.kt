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
import com.mcqapp.domain.stats.CardCountsData
import com.mcqapp.domain.stats.StatsBundle
import com.mcqapp.domain.stats.WeakCard
import com.mcqapp.ui.stats.WeakCardRow
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.DonutChart
import com.mcqapp.ui.stats.charts.LegendDot
import com.mcqapp.ui.stats.charts.seriesColors

/**
 * Tab 5: the collection's current shape — the state donut with its legend and
 * the flagged leeches.
 */
@Composable
internal fun CardsSection(
    bundle: StatsBundle,
    questionText: Map<String, String>,
    onOpenCard: (WeakCard) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { CardCountsCard(bundle.cardCounts) }
        if (bundle.leeches.isNotEmpty()) {
            item { LeechesCard(bundle, questionText, onOpenCard) }
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
private fun LeechesCard(
    bundle: StatsBundle,
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
