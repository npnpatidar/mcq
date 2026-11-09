package com.mcqapp.ui.stats.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.TestProgressData
import com.mcqapp.ui.common.familyLabel
import com.mcqapp.ui.common.testTypeLabel
import kotlin.math.roundToInt

/**
 * The Tests tab (stats.md §13 phase 2): attempt families kept apart, the last
 * focus-practice runs, and the scope's leech/at-risk/unseen counters. Nothing
 * here averages a practice score into a mock score.
 */
@Composable
internal fun TestsSection(data: TestProgressData) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "families") { FamiliesCard(data) }
        item(contentType = "reliability") { ReliabilityCard(data) }
        item(contentType = "practice") { PracticeCard(data) }
        item(contentType = "practice-topics") { PracticeTopicsCard(data) }
        item(contentType = "panels") { PanelsCard(data) }
        item(contentType = "scope") { ScopeCard(data) }
    }
}

@Composable
private fun FamiliesCard(data: TestProgressData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_tests_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.stats_tests_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (data.families.isEmpty()) {
                Text(
                    stringResource(R.string.stats_tests_no_attempts),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                data.families.forEach { family ->
                    Text(
                        stringResource(
                            R.string.stats_tests_family_row,
                            familyLabel(family.family),
                            family.attempts,
                            family.latestPercent.roundToInt(),
                            family.averagePercent.roundToInt()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PracticeCard(data: TestProgressData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_practice_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            if (data.practiceRuns.isEmpty()) {
                Text(
                    stringResource(R.string.stats_practice_no_runs),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                data.practiceRuns.forEach { run ->
                    Text(
                        stringResource(
                            R.string.stats_practice_run,
                            testTypeLabel(run.type),
                            run.targetedCorrect,
                            run.targetedAsked,
                            (run.targetedAccuracy * 100).roundToInt(),
                            run.mistakesCleared
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * The weak-topic accuracy view: what the recorded practice targets scored
 * across the recent runs, weakest first. A target with no asked question is
 * absent, so the card never shows a 0/0 as "0%".
 */
@Composable
private fun PracticeTopicsCard(data: TestProgressData) {
    if (data.practiceTopics.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_practice_topics_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.stats_practice_topics_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            data.practiceTopics.forEach { topic ->
                Text(
                    stringResource(
                        R.string.stats_practice_topics_row,
                        topic.title,
                        topic.correct,
                        topic.asked,
                        (topic.accuracy * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Fixed-panel progress: the same frozen ids repeated, so a change is the
 * learner's rather than the sample's. Only panels that have been run appear;
 * a panel with no run has nothing to report yet.
 */
@Composable
private fun PanelsCard(data: TestProgressData) {
    if (data.panels.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_panels_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.stats_panels_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            data.panels.forEach { panel ->
                Text(
                    stringResource(
                        R.string.stats_panels_row,
                        panel.name,
                        panel.size,
                        panel.attempts,
                        panel.latestPercent.roundToInt(),
                        panel.averagePercent.roundToInt()
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ReliabilityCard(data: TestProgressData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_reliability_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.stats_reliability_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val rel = data.reliability
            if (rel == null) {
                Text(
                    stringResource(R.string.stats_reliability_too_small),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    stringResource(R.string.stats_reliability_value, rel.reliability, rel.semPoints),
                    style = MaterialTheme.typography.bodySmall
                )
                rel.kr20?.let { kr ->
                    Text(
                        stringResource(R.string.stats_reliability_kr20, kr),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeCard(data: TestProgressData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_progress_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.stats_progress_line,
                    data.scopeSize,
                    data.unseen,
                    data.leeches,
                    data.atRisk
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(R.string.stats_progress_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
