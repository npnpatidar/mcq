package com.mcqapp.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.ReviewGrade
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The per-card drill-down, Anki's Card Info: the question, its memory state
 * right now, and its full logged review history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardInfoSheet(detail: StatsViewModel.CardDetail, onDismiss: () -> Unit) {
    val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM yy, HH:mm", Locale.ENGLISH)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                detail.text ?: detail.questionId,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.padding(top = 8.dp))
            val state = detail.state
            if (state == null) {
                Text(
                    stringResource(R.string.stats_no_schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                StatLine(R.string.stats_state, stateLabel(state.state))
                StatLine(R.string.stats_interval_label, "${state.intervalDays} d")
                val dueLabel = when {
                    state.dueAt == 0L -> stringResource(R.string.stats_never)
                    else -> Instant.ofEpochMilli(state.dueAt).atOffset(ZoneOffset.UTC)
                        .format(dateTimeFormat)
                }
                StatLine(R.string.stats_due_label, dueLabel)
                StatLine(
                    R.string.stats_stability_label,
                    state.stability.takeIf { it > 0.0 }?.let { "%.1f d".format(Locale.ENGLISH, it) } ?: "—"
                )
                StatLine(
                    R.string.stats_difficulty_label,
                    state.difficulty.takeIf { it > 0.0 }?.let { "%.1f".format(Locale.ENGLISH, it) } ?: "—"
                )
                StatLine(
                    R.string.stats_predicted,
                    detail.retrievability?.let { "${(it * 100).toInt()}%" } ?: "—"
                )
                StatLine(R.string.stats_lapses, state.lapses.toString())
                StatLine(R.string.stats_reviews_label, state.reviewCount.toString())
            }
            Spacer(Modifier.padding(top = 16.dp))
            Text(
                stringResource(R.string.stats_history),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.padding(top = 4.dp))
            if (detail.history.isEmpty()) {
                Text(
                    stringResource(R.string.stats_no_history),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                detail.history.reversed().forEach { entry ->
                    HistoryRow(entry, dateTimeFormat)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: com.mcqapp.domain.stats.ReviewHistoryEntry, format: DateTimeFormatter) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Instant.ofEpochMilli(entry.reviewedAt).atOffset(ZoneOffset.UTC).format(format),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                gradeLabel(entry.rating),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = gradeColor(entry.rating)
            )
        }
        Text(
            historyDetail(entry),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun gradeLabel(rating: Int): String = when (rating) {
    1 -> stringResource(R.string.grade_again)
    2 -> stringResource(R.string.grade_hard)
    3 -> stringResource(R.string.grade_good)
    else -> stringResource(R.string.grade_easy)
}

@Composable
private fun gradeColor(rating: Int): androidx.compose.ui.graphics.Color = when (rating) {
    1 -> MaterialTheme.colorScheme.error
    2 -> MaterialTheme.colorScheme.tertiary
    3 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.secondary
}

/** "6s · S 4.2 · D 5.0 · I 10d", from what the row recorded. */
internal fun historyDetail(entry: com.mcqapp.domain.stats.ReviewHistoryEntry): String = buildString {
    entry.elapsedSeconds?.let { append(it).append("s · ") }
    entry.stabilityAfter?.let { append("S ").append("%.1f".format(Locale.ENGLISH, it)).append(" · ") }
    entry.difficultyAfter?.let { append("D ").append("%.1f".format(Locale.ENGLISH, it)).append(" · ") }
    entry.intervalDaysAfter?.let { append("I ").append(it).append("d") }
}.trimEnd(' ', '·')

@Composable
private fun stateLabel(kind: CardStateKind): String = when (kind) {
    CardStateKind.NEW -> stringResource(R.string.stats_new_cards)
    CardStateKind.LEARNING -> stringResource(R.string.stats_learning)
    CardStateKind.REVIEW -> stringResource(R.string.stats_review_kind)
    CardStateKind.RELEARNING -> stringResource(R.string.stats_relearning)
}
