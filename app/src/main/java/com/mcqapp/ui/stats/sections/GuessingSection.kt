package com.mcqapp.ui.stats.sections

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.GuessBand
import com.mcqapp.domain.stats.GuessingData
import com.mcqapp.domain.stats.ShakyCard
import com.mcqapp.domain.stats.StackedPoint
import com.mcqapp.ui.stats.KpiGrid
import com.mcqapp.ui.stats.KpiItem
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.LegendDot
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.MeterBar
import com.mcqapp.ui.stats.charts.StackedBarChart
import com.mcqapp.ui.stats.charts.formatPct
import com.mcqapp.ui.stats.charts.formatSeconds
import com.mcqapp.ui.stats.charts.seriesColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The guessing tab: what the declared-guess switch is worth.
 *
 * Every figure counts flagged reviews only. The page says how much of the
 * in-range history that leaves out, because rows written before v19 carry no
 * flag — treating them as "earned" would quietly flatter the numbers.
 *
 * Reading order is a funnel: how often you guess and how often it works
 * (KPIs, 2×2, trend), when it happens (maturity, sitting position, hour),
 * what it costs (dwell, schedule), and where it is concentrated (topics,
 * shaky cards).
 */
@Composable
internal fun GuessingSection(
    bundle: GuessingData,
    questionText: Map<String, String>,
    onOpenCard: (ShakyCard) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "kpis") { KpiCard(bundle) }
        item(contentType = "outcomes") { OutcomesCard(bundle) }
        item(contentType = "trend") { TrendCard(bundle) }
        item(contentType = "maturity") { MaturityCard(bundle) }
        item(contentType = "position") { PositionCard(bundle) }
        item(contentType = "hourly") { HourlyCard(bundle) }
        item(contentType = "dwell") { DwellCard(bundle) }
        item(contentType = "cost") { CostCard(bundle) }
        item(contentType = "topics") { TopicsCard(bundle) }
        item(contentType = "shaky") { ShakyCardList(bundle, questionText, onOpenCard) }
    }
}

@Composable
private fun KpiCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-kpis",
        title = { ChartTitle(R.string.stats_guess_kpis) },
        subtitle = { ChartSubtitle(R.string.stats_guess_kpis_help) }
    ) {
        if (!bundle.hasData) {
            ChartEmpty()
            return@ChartCard
        }
        KpiGrid(
            items = listOf(
                KpiItem(
                    labelRes = R.string.stats_guess_rate,
                    value = bundle.guessRate?.let { formatPct(it) }
                        ?: stringResource(R.string.stats_no_data)
                ),
                KpiItem(
                    labelRes = R.string.stats_guess_hit_rate,
                    value = bundle.hitRate?.let { formatPct(it) }
                        ?: stringResource(R.string.stats_no_data)
                ),
                KpiItem(
                    labelRes = R.string.stats_guess_chance,
                    value = bundle.chanceBaseline?.let { formatPct(it) }
                        ?: stringResource(R.string.stats_no_data)
                ),
                KpiItem(
                    labelRes = R.string.stats_guess_lift,
                    value = bundle.lift?.let { formatPoints(it) }
                        ?: stringResource(R.string.stats_no_data)
                ),
                KpiItem(
                    labelRes = R.string.stats_guess_raw_accuracy,
                    value = bundle.rawAccuracy?.let { formatPct(it) }
                        ?: stringResource(R.string.stats_no_data)
                ),
                KpiItem(
                    labelRes = R.string.stats_guess_graded_pass,
                    value = bundle.gradedPassRate?.let { formatPct(it) }
                        ?: stringResource(R.string.stats_no_data)
                )
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (bundle.isComplete) {
                stringResource(R.string.stats_guess_coverage_complete, bundle.reviews)
            } else {
                stringResource(
                    R.string.stats_guess_coverage, bundle.reviews, bundle.allReviews
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OutcomesCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-outcomes",
        title = { ChartTitle(R.string.stats_guess_outcomes) },
        subtitle = { ChartSubtitle(R.string.stats_guess_outcomes_help) }
    ) {
        if (!bundle.hasData) {
            ChartEmpty()
            return@ChartCard
        }
        val total = bundle.reviews.coerceAtLeast(1)
        KpiGrid(
            items = listOf(
                outcome(bundle.earned, total, R.string.stats_guess_earned),
                outcome(bundle.lucky, total, R.string.stats_guess_lucky),
                outcome(bundle.lapse, total, R.string.stats_guess_lapse),
                outcome(bundle.didntKnow, total, R.string.stats_guess_didnt_know)
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.stats_guess_accuracy_gap,
                formatPoints((bundle.rawAccuracy ?: 0.0) - (bundle.gradedPassRate ?: 0.0))
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun outcome(count: Int, total: Int, @StringRes labelRes: Int): KpiItem = KpiItem(
    labelRes = labelRes,
    value = stringResource(
        R.string.stats_guess_outcome_value,
        count,
        formatPct(count.toDouble() / total)
    )
)

@Composable
private fun TrendCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-trend",
        title = { ChartTitle(R.string.stats_guess_trend) },
        subtitle = { ChartSubtitle(R.string.stats_guess_trend_help) }
    ) {
        if (bundle.trend.none { it.reviews > 0 }) {
            ChartEmpty()
            return@ChartCard
        }
        val colors = seriesColors()
        LineChart(
            labels = bundle.trend.map { it.label },
            series = listOf(
                LineSeries(bundle.trend.map { it.guessRate }, colors[0], fill = true),
                LineSeries(bundle.trend.map { it.hitRate }, colors[1])
            ),
            fixedMax = 1.0,
            referenceLine = bundle.chanceBaseline,
            yLabel = { formatPct(it) }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendEntry(colors[0], R.string.stats_guess_rate)
            LegendEntry(colors[1], R.string.stats_guess_hit_rate)
        }
    }
}

@Composable
private fun MaturityCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-maturity",
        title = { ChartTitle(R.string.stats_guess_maturity) },
        subtitle = { ChartSubtitle(R.string.stats_guess_maturity_help) }
    ) {
        val bands = bundle.maturity.filter { it.reviews > 0 }
        if (bands.isEmpty()) {
            ChartEmpty()
            return@ChartCard
        }
        val colors = seriesColors()
        StackedBarChart(
            points = bands.map { band ->
                StackedPoint(
                    label = stringResource(bandLabel(band.band)),
                    segments = listOf(
                        band.guessed.toDouble(),
                        (band.reviews - band.guessed).toDouble()
                    ),
                    total = band.reviews
                )
            },
            segmentCount = 2,
            overlay = bands.map { it.hitRate }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendEntry(colors[0], R.string.stats_guess_guessed)
            LegendEntry(colors[1], R.string.stats_guess_earned_reviews)
        }
    }
}

@Composable
private fun PositionCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-position",
        title = { ChartTitle(R.string.stats_guess_position) },
        subtitle = { ChartSubtitle(R.string.stats_guess_position_help) }
    ) {
        val positions = bundle.positions.filter { it.reviews > 0 }
        if (positions.isEmpty()) {
            ChartEmpty()
            return@ChartCard
        }
        val colors = seriesColors()
        StackedBarChart(
            points = positions.map { slice ->
                StackedPoint(
                    label = slice.label,
                    segments = listOf(
                        slice.guessed.toDouble(),
                        (slice.reviews - slice.guessed).toDouble()
                    ),
                    total = slice.reviews
                )
            },
            segmentCount = 2,
            overlay = positions.map { it.hitRate }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendEntry(colors[0], R.string.stats_guess_guessed)
            LegendEntry(colors[1], R.string.stats_guess_earned_reviews)
        }
    }
}

@Composable
private fun HourlyCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-hourly",
        title = { ChartTitle(R.string.stats_guess_hourly) },
        subtitle = { ChartSubtitle(R.string.stats_guess_hourly_help) }
    ) {
        if (bundle.hourly.all { it.reviews == 0 }) {
            ChartEmpty()
            return@ChartCard
        }
        val colors = seriesColors()
        StackedBarChart(
            points = bundle.hourly.map { hour ->
                StackedPoint(
                    label = hour.hour.toString(),
                    segments = listOf(
                        hour.guessed.toDouble(),
                        (hour.reviews - hour.guessed).toDouble()
                    ),
                    total = hour.reviews
                )
            },
            segmentCount = 2,
            overlay = bundle.hourly.map { it.guessRate }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendEntry(colors[0], R.string.stats_guess_guessed)
            LegendEntry(colors[1], R.string.stats_guess_earned_reviews)
        }
    }
}

@Composable
private fun DwellCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-dwell",
        title = { ChartTitle(R.string.stats_guess_dwell) },
        subtitle = { ChartSubtitle(R.string.stats_guess_dwell_help) }
    ) {
        val guessed = bundle.dwell.guessedAvgSeconds
        val earned = bundle.dwell.earnedAvgSeconds
        if (guessed == null && earned == null) {
            ChartEmpty()
            return@ChartCard
        }
        Text(
            stringResource(
                R.string.stats_guess_dwell_guessed,
                guessed?.let { formatSeconds(it) } ?: "—",
                bundle.dwell.guessedReviews
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(
                R.string.stats_guess_dwell_earned,
                earned?.let { formatSeconds(it) } ?: "—",
                bundle.dwell.earnedReviews
            ),
            style = MaterialTheme.typography.bodySmall
        )
        if (guessed != null && earned != null) {
            val delta = earned - guessed
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    delta > 0.5 -> stringResource(
                        R.string.stats_guess_dwell_faster, formatSeconds(delta)
                    )
                    delta < -0.5 -> stringResource(
                        R.string.stats_guess_dwell_slower, formatSeconds(-delta)
                    )
                    else -> stringResource(R.string.stats_guess_dwell_even)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CostCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-cost",
        title = { ChartTitle(R.string.stats_guess_cost) },
        subtitle = { ChartSubtitle(R.string.stats_guess_cost_help) }
    ) {
        val cost = bundle.cost
        if (cost.reviews == 0) {
            ChartEmpty(R.string.stats_guess_cost_none)
            return@ChartCard
        }
        Text(
            stringResource(R.string.stats_guess_cost_days, cost.extraDays),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.stats_guess_cost_detail,
                cost.reviews,
                formatDays(cost.avgGoodDays),
                formatDays(cost.avgActualDays)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TopicsCard(bundle: GuessingData) {
    ChartCard(
        testTag = "stats-guessing-topics",
        title = { ChartTitle(R.string.stats_guess_topics) },
        subtitle = { ChartSubtitle(R.string.stats_guess_topics_help) }
    ) {
        if (bundle.topics.isEmpty()) {
            ChartEmpty(R.string.stats_guess_no_topics)
            return@ChartCard
        }
        val color = seriesColors()[0]
        bundle.topics.forEach { topic ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        topic.label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(
                            R.string.stats_guess_topic_row,
                            topic.reviews,
                            topic.guessed,
                            topic.hitRate?.let { formatPct(it) } ?: "—"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(3.dp))
                MeterBar(topic.guessRate ?: 0.0, color)
            }
        }
    }
}

@Composable
private fun ShakyCardList(
    bundle: GuessingData,
    questionText: Map<String, String>,
    onOpenCard: (ShakyCard) -> Unit
) {
    ChartCard(
        testTag = "stats-guessing-shaky",
        title = { ChartTitle(R.string.stats_guess_shaky) },
        subtitle = { ChartSubtitle(R.string.stats_guess_shaky_help) }
    ) {
        if (bundle.shaky.isEmpty()) {
            ChartEmpty(R.string.stats_guess_no_shaky)
            return@ChartCard
        }
        bundle.shaky.forEach { card ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenCard(card) }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    questionText[card.questionId] ?: card.questionId,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(
                        R.string.stats_guess_shaky_row,
                        card.guessed,
                        card.reviews,
                        card.hitRate?.let { formatPct(it) } ?: "—"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

@StringRes
private fun bandLabel(band: GuessBand): Int = when (band) {
    GuessBand.FRESH -> R.string.stats_guess_band_fresh
    GuessBand.LEARNING -> R.string.stats_guess_band_learning
    GuessBand.RELEARNING -> R.string.stats_guess_band_relearning
    GuessBand.YOUNG -> R.string.stats_guess_band_young
    GuessBand.MATURE -> R.string.stats_guess_band_mature
}

/** "+18 pts" / "-4 pts": a rate difference in percentage points. */
private fun formatPoints(delta: Double): String {
    val points = (delta * 100).roundToInt()
    return if (points >= 0) "+$points pts" else "$points pts"
}

private fun formatDays(days: Double?): String =
    if (days == null) "—" else String.format(Locale.ENGLISH, "%.1f d", days)
