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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import android.app.Application
import androidx.compose.ui.platform.LocalContext
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.QuestionResult
import com.mcqapp.ui.ResultsViewModelFactory
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage
import java.text.SimpleDateFormat
import java.util.Date
import com.mcqapp.R

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
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reviewMode) "Attempt review" else "Results") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        val showLoading = com.mcqapp.util.rememberDelayedVisibility(state.loading)
        if (showLoading) {
            Text(
                stringResource(R.string.loading),
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }
        if (state.loading) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            return@Scaffold
        }

        state.loadError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        val attempt = state.attempt
        if (attempt == null) {
            Text(
                stringResource(R.string.attempt_not_found),
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        // Rows stream in a page at a time; the header numbers and tallies come
        // from SQL aggregates, so a 250k attempt never sits in memory whole.
        val items = viewModel.pagingData.collectAsLazyPagingItems()
        val filterValue = state.filter
        val bookmarked = state.bookmarked
        val aggregates = state.aggregates
        val ungradedCount = aggregates.ungraded
        // Old attempts (finished before per-question timing existed)
        // carry all-zero dwell: hide times entirely rather than
        // showing a wall of 0:00.
        val showDwell = aggregates.dwellCount > 0
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(attempt.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        SimpleDateFormat("dd MMM yyyy, HH:mm", LocalConfiguration.current.locales[0])
                            .format(Date(attempt.finishedAt)),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.l_1f_0f).format(attempt.score, attempt.maxScore),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.l_0f).format(attempt.percentage),
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
                        if (ungradedCount > 0) {
                            Stat(
                                "Ungraded",
                                ungradedCount.toString(),
                                MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    val minutes = attempt.durationSeconds / 60
                    val seconds = attempt.durationSeconds % 60
                    Text(
                        stringResource(R.string.time_taken_02d_02d).format(minutes, seconds),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    val totalDwell = aggregates.totalDwell
                    if (aggregates.total > 0 && totalDwell > 0) {
                        Text(
                            "Avg ${com.mcqapp.domain.Dwell.format(totalDwell / aggregates.total)} per question",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            }
            item {
            if (state.categoryBreakdown.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.category_breakdown), style = MaterialTheme.typography.titleSmall)
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
                                    if (total > 0) "$correct/$total" else "Not scored",
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
            }

            item {
                Text(stringResource(R.string.questions), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val chips = buildList {
                        add("All")
                        add("Correct")
                        add("Wrong")
                        add("Skipped")
                        if (ungradedCount > 0) add("Ungraded")
                        if (bookmarked.isNotEmpty()) add("Saved")
                    }
                    chips.forEach { label ->
                        FilterChip(
                            selected = filterValue == label,
                            onClick = { viewModel.updateFilter(label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
            val refresh = items.loadState.refresh
            if (refresh is LoadState.NotLoading && items.itemCount == 0) {
                item {
                    Text(
                        "No ${filterValue.lowercase()} questions",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.result.questionId }
                ) { index ->
                    val numbered = items[index] ?: return@items
                    ResultCard(
                        index = numbered.index + 1,
                        result = numbered.result,
                        bookmarked = numbered.result.questionId in bookmarked,
                        onToggleBookmark = { viewModel.toggleBookmark(numbered.result.questionId) },
                        showDwell = showDwell
                    )
                }
            }
            if (items.loadState.append is LoadState.Loading) {
                item(key = "results-load-more") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
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
private fun ResultCard(
    index: Int,
    result: QuestionResult,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    showDwell: Boolean = false
) {
    val ungraded = result.correctOptionIds.isEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = when {
                ungraded -> MaterialTheme.colorScheme.surfaceVariant
                result.isCorrect -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$index.",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
                ContentElements(
                    result.elements,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (ungraded) "Not scored" else if (result.isCorrect) "Correct" else "Wrong",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ungraded) MaterialTheme.colorScheme.tertiary
                    else if (result.isCorrect)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark question"
                    )
                }
            }
            // QuestionResult carries the question's content elements rather than
            // a separate image field, so the question image is rendered with
            // them above; this call was a guaranteed no-op.
            result.options.forEach { option ->
                val isCorrect = option.id in result.correctOptionIds
                val wasSelected = option.id in result.selectedOptionIds
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        if (isCorrect) "✓" else if (wasSelected && !ungraded) "✗"
                        // Filled circle, matching study: a bullet renders smaller.
                        else if (wasSelected) "●" else "○",
                        color = if (isCorrect)
                            MaterialTheme.colorScheme.primary
                        else if (wasSelected && !ungraded)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(20.dp)
                    )
                    ContentElements(option.elements, textStyle = MaterialTheme.typography.bodySmall)
                }
            }
            if (result.explanationElements.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = stringResource(R.string.explanation),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    ContentElements(
                        result.explanationElements,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (showDwell) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Time spent: ${com.mcqapp.domain.Dwell.format(result.dwellSeconds)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            QuestionImage(src = result.explanationImage, contentDescription = stringResource(R.string.explanation_image))
        }
    }
}
