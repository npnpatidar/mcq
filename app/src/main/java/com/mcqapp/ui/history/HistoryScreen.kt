package com.mcqapp.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.mcqapp.ui.theme.verdictColors
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Trends
import java.text.SimpleDateFormat
import java.util.Date
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: HistoryViewModel = viewModel()
) {
    val attempts by viewModel.attempts.collectAsStateWithLifecycle()
    val hardest by viewModel.hardest.collectAsStateWithLifecycle()
    val weakest by viewModel.weakest.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (attempts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_attempts_yet))
            }
        } else {
            // Hoisted: was recomputed inside the lazy content lambda on every
        // list build.
        val trends = remember(attempts) { Trends.perPaper(attempts) }
        LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (trends.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.trends), style = MaterialTheme.typography.titleSmall)
                    }
                    items(trends, key = { "trend-${it.paperId}" }) { trend ->
                        TrendCard(trend = trend)
                    }
                    item {
                        Text(stringResource(R.string.attempts), style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (weakest.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.weakest_categories), style = MaterialTheme.typography.titleSmall)
                    }
                    items(weakest, key = { "weak-${it.paperId}-${it.categoryTitle}" }) { mastery ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    mastery.categoryTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    "${mastery.paperTitle} · ${"%.0f%%".format(mastery.rate * 100)}" +
                                        " · ${mastery.correct}/${mastery.graded}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { mastery.rate.toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                if (hardest.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.hardest_questions), style = MaterialTheme.typography.titleSmall)
                    }
                    items(hardest, key = { "hard-${it.questionId}" }) { stat ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    stat.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    "${"%.0f%%".format(stat.wrongRate * 100)} wrong" +
                                        " · ${"%.0f%%".format(stat.skipRate * 100)} skipped" +
                                        " · ${stat.gradedAttempts} attempts",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                items(attempts, key = { it.id }) { attempt ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(com.mcqapp.ui.navigation.resultsRoute(attempt.id)) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(attempt.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", LocalConfiguration.current.locales[0])
                                        .format(Date(attempt.finishedAt)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    stringResource(R.string.l_0f).format(attempt.percentage),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${attempt.correctCount}/${attempt.totalQuestions} correct",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendCard(trend: Trends.PaperTrend) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(trend.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${trend.attempts} attempt${if (trend.attempts == 1) "" else "s"}" +
                        " · best ${"%.0f%%".format(trend.bestPercent)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(R.string.l_0f).format(trend.latestPercent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (trend.attempts > 1) {
                    val (arrow, color) = when {
                        trend.deltaPoints > 0.005 -> "▲" to verdictColors().rising
                        trend.deltaPoints < -0.005 -> "▼" to verdictColors().falling
                        else -> "=" to Color.Gray
                    }
                    Text(
                        "$arrow ${"%.0f".format(kotlin.math.abs(trend.deltaPoints))} pts",
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
            }
        }
    }
}
