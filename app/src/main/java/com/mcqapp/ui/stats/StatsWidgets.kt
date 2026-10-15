package com.mcqapp.ui.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.WeakCard
import com.mcqapp.ui.stats.charts.formatPct
import kotlin.math.roundToInt

/** One headline figure with its caption. */
internal data class KpiItem(
    @StringRes val labelRes: Int,
    val value: String,
    val valueColor: Color? = null
)

/** A compact grid of [KpiItem]s, [columns] per row. */
@Composable
internal fun KpiGrid(items: List<KpiItem>, columns: Int = 2, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    KpiTile(item, Modifier.weight(1f))
                }
                if (rowItems.size < columns) {
                    Spacer(Modifier.weight((columns - rowItems.size).toFloat()))
                }
            }
        }
    }
}

@Composable
private fun KpiTile(item: KpiItem, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            item.value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = item.valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(item.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** A single "label · value" line used in the today strip and detail sheets. */
@Composable
internal fun StatLine(
    @StringRes labelRes: Int,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The true-retention table's header row. */
@Composable
internal fun RetentionTableHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.labelSmall
        )
        RetentionHeaderCell(R.string.stats_col_all)
        RetentionHeaderCell(R.string.stats_col_young)
        RetentionHeaderCell(R.string.stats_col_mature)
    }
}

@Composable
private fun RetentionHeaderCell(@StringRes labelRes: Int) {
    Text(
        stringResource(labelRes),
        modifier = Modifier.width(64.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End
    )
}

/** One true-retention row: period label plus three "pct · n" cells. */
@Composable
internal fun RetentionTableRow(
    label: String,
    all: Pair<Double?, Int>,
    young: Pair<Double?, Int>,
    mature: Pair<Double?, Int>,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall
        )
        RetentionValueCell(all)
        RetentionValueCell(young)
        RetentionValueCell(mature)
    }
}

@Composable
private fun RetentionValueCell(cell: Pair<Double?, Int>) {
    Column(modifier = Modifier.width(64.dp), horizontalAlignment = Alignment.End) {
        Text(
            cell.first?.let { formatPct(it) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            cell.second.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A ranked problem-card row: question text on top, its lifetime figures
 * underneath. Tap opens the card's detail sheet.
 */
@Composable
internal fun WeakCardRow(
    card: WeakCard,
    questionText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(
            questionText ?: card.questionId,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            statsCardSummary(card),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "5× · 3× again · 60% · 12s" — compact data summary for a problem card. */
internal fun statsCardSummary(card: WeakCard): String = buildString {
    append(card.reviews).append("× · ")
    append(card.lapses).append("× again · ")
    append(formatPct(card.againRate))
    card.avgSeconds?.let { append(" · ").append(it.roundToInt()).append("s") }
}
