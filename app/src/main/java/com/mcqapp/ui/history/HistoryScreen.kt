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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Trends
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: HistoryViewModel = viewModel()
) {
    val attempts by viewModel.attempts.collectAsState()
    val hardest by viewModel.hardest.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Text("No attempts yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val trends = Trends.perPaper(attempts)
                if (trends.isNotEmpty()) {
                    item {
                        Text("Trends", style = MaterialTheme.typography.titleSmall)
                    }
                    items(trends, key = { "trend-${it.paperId}" }) { trend ->
                        TrendCard(trend = trend)
                    }
                    item {
                        Text("Attempts", style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (hardest.isNotEmpty()) {
                    item {
                        Text("Hardest questions", style = MaterialTheme.typography.titleSmall)
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
                            .clickable { navController.navigate("review/${attempt.id}") }
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
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                        .format(Date(attempt.finishedAt)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "%.0f%%".format(attempt.percentage),
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
                    "%.0f%%".format(trend.latestPercent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (trend.attempts > 1) {
                    val (arrow, color) = when {
                        trend.deltaPoints > 0.005 -> "▲" to Color(0xFF2E7D32)
                        trend.deltaPoints < -0.005 -> "▼" to Color(0xFFC62828)
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
