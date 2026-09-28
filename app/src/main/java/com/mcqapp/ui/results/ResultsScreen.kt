package com.mcqapp.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.app.Application
import androidx.compose.ui.platform.LocalContext
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.QuestionResult
import com.mcqapp.ui.ResultsViewModelFactory
import com.mcqapp.util.QuestionImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(
    repository: McqRepository,
    attemptId: Long,
    navController: NavController,
    reviewMode: Boolean
) {
    val viewModel: ResultsViewModel = viewModel(
        key = "results-$attemptId",
        factory = ResultsViewModelFactory(
            LocalContext.current.applicationContext as Application,
            attemptId
        )
    )
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reviewMode) "Attempt review" else "Results") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (state.loading) {
            Text(
                "Loading…",
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        val attempt = state.attempt
        if (attempt == null) {
            Text(
                "Attempt not found",
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(attempt.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                            .format(Date(attempt.finishedAt)),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "%.1f / %.0f".format(attempt.score, attempt.maxScore),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "%.0f%%".format(attempt.percentage),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            (attempt.percentage / 100.0).toFloat().coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Stat("Correct", attempt.correctCount.toString(), MaterialTheme.colorScheme.primary)
                        Stat("Wrong", attempt.wrongCount.toString(), MaterialTheme.colorScheme.error)
                        Stat("Skipped", attempt.skippedCount.toString(), MaterialTheme.colorScheme.outline)
                    }
                    val minutes = attempt.durationSeconds / 60
                    val seconds = attempt.durationSeconds % 60
                    Text(
                        "Time taken: %02d:%02d".format(minutes, seconds),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            if (state.categoryBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Category breakdown", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        state.categoryBreakdown.forEach { (title, pair) ->
                            val (correct, total) = pair
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "$correct/$total",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (total > 0) correct.toFloat() / total else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Questions", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val filter = remember { mutableStateOf("All") }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Correct", "Wrong", "Skipped").forEach { label ->
                    FilterChip(
                        selected = filter.value == label,
                        onClick = { filter.value = label },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val filtered = when (filter.value) {
                "Correct" -> state.results.filter { it.isCorrect }
                "Wrong" -> state.results.filter { !it.isCorrect && it.selectedOptionIds.isNotEmpty() }
                "Skipped" -> state.results.filter { it.selectedOptionIds.isEmpty() }
                else -> state.results
            }
            if (filtered.isEmpty()) {
                Text(
                    "No ${filter.value.lowercase()} questions",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            filtered.forEachIndexed { index, result ->
                ResultCard(index = index + 1, result = result)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ResultCard(index: Int, result: QuestionResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (result.isCorrect)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$index.",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    result.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (result.isCorrect) "Correct" else "Wrong",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (result.isCorrect)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
            QuestionImage(src = null, modifier = Modifier.padding(top = 4.dp))
            result.options.forEach { option ->
                val isCorrect = option.id in result.correctOptionIds
                val wasSelected = option.id in result.selectedOptionIds
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        if (isCorrect) "✓" else if (wasSelected) "✗" else "○",
                        color = if (isCorrect)
                            MaterialTheme.colorScheme.primary
                        else if (wasSelected)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(option.text, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (result.explanation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Explanation: ${result.explanation}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            QuestionImage(src = result.explanationImage)
        }
    }
}
