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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import android.app.Application
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Dwell
import com.mcqapp.domain.QuestionResult
import com.mcqapp.domain.QuestionReview
import com.mcqapp.domain.ReportFamily
import com.mcqapp.domain.ReviewTag
import com.mcqapp.domain.ReviewFilters
import kotlinx.coroutines.launch
import android.widget.Toast
import com.mcqapp.domain.TestReport
import com.mcqapp.domain.TestType
import com.mcqapp.ui.ResultsViewModelFactory
import com.mcqapp.ui.common.PagingAppendErrorItem
import com.mcqapp.ui.common.PagingRefreshErrorItem
import com.mcqapp.ui.common.attemptTypeLine
import com.mcqapp.ui.common.testTypeLabel
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
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

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // LocalResources (not LocalContext.current) keeps the chooser title
    // configuration-aware: LocalContext.getString() is not re-read when the
    // Configuration changes (LocalContextGetResourceValueCall).
    val resources = LocalResources.current
    var showExportMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reviewMode) "Attempt review" else "Results") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.report != null) {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.report_export))
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.report_export_csv)) },
                                onClick = {
                                    showExportMenu = false
                                    scope.launch {
                                        val intent = viewModel.exportCsvIntent()
                                        if (intent != null) {
                                            context.startActivity(
                                                android.content.Intent.createChooser(
                                                    intent,
                                                    resources.getString(R.string.report_export_share)
                                                )
                                            )
                                        } else {
                                            Toast.makeText(context, R.string.report_export_failed, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.report_export_pdf)) },
                                onClick = {
                                    showExportMenu = false
                                    scope.launch {
                                        val intent = viewModel.exportPdfIntent()
                                        if (intent != null) {
                                            context.startActivity(
                                                android.content.Intent.createChooser(
                                                    intent,
                                                    resources.getString(R.string.report_export_share)
                                                )
                                            )
                                        } else {
                                            Toast.makeText(context, R.string.report_export_failed, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
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

        // Rows stream in a page at a time; the header numbers come from the
        // derived report, which reads one light projection of the attempt.
        val items = viewModel.pagingData.collectAsLazyPagingItems()
        val filterValue = state.filter
        val bookmarked = state.bookmarked
        val report = state.report
        val showDwell = report?.timing?.hasDwell ?: (state.aggregates.dwellCount > 0)
        val ungradedCount = report?.score?.ungraded ?: state.aggregates.ungraded
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Tagged so the instrumented review can scroll to a card below
                // the header cards without depending on its index.
                .testTag("results-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(contentType = "attempt") {
                if (report != null) {
                    ReportHeaderCard(report)
                } else {
                    // The attempt loaded but the report could not be derived;
                    // show the stored header rather than a blank page.
                    StoredHeaderCard(attempt)
                }
            }
            if (report != null) {
                item(contentType = "meaning") { MeaningCard(report) }
                if (report.practice != null) {
                    item(contentType = "practice") { PracticeCard(report) }
                }
                if (report.memory != null) {
                    item(contentType = "memory") { MemoryCard(report) }
                }
                if (report.coverage != null || report.mistakes != null) {
                    item(contentType = "coverage") { CoverageCard(report) }
                }
                if (report.trend != null) {
                    item(contentType = "trend") { TrendCard(report) }
                }
                if (report.interval != null) {
                    item(contentType = "interval") { IntervalCard(report) }
                }
                if (report.errorMix.hasAnything) {
                    item(contentType = "error-mix") { ErrorMixCard(report) }
                }
                if (report.topics.isNotEmpty()) {
                    item(contentType = "topics") { TopicsCard(report) }
                }
                if (report.timing.hasDwell) {
                    item(contentType = "timing") { TimingCard(report) }
                }
                if (report.actions.isNotEmpty()) {
                    item(contentType = "actions") {
                        ActionsCard(
                            report = report,
                            onStudy = { ids ->
                                navController.navigate(
                                    com.mcqapp.ui.navigation.studyRoute(report.attempt.paperId, ids)
                                )
                            },
                            onTest = { type, count, ids ->
                                navController.navigate(
                                    com.mcqapp.ui.navigation.testRoute(
                                        paperId = report.attempt.paperId,
                                        categoryIds = ids,
                                        type = type.name,
                                        count = count
                                    )
                                )
                            },
                            onReview = { filter -> viewModel.updateFilter(filter) }
                        )
                    }
                }
            }

            item(contentType = "filter") {
                Text(stringResource(R.string.questions), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val chips = buildList {
                        add(ReviewFilters.ALL)
                        add(ReviewFilters.CORRECT)
                        add(ReviewFilters.WRONG)
                        add(ReviewFilters.SKIPPED)
                        if (ungradedCount > 0) add(ReviewFilters.UNGRADED)
                        if (showDwell) add(ReviewFilters.SLOW)
                        if (bookmarked.isNotEmpty()) add(ReviewFilters.SAVED)
                    }
                    chips.forEach { label ->
                        FilterChip(
                            selected = filterValue == label,
                            onClick = { viewModel.updateFilter(label) },
                            label = {
                                Text(
                                    if (label == ReviewFilters.SLOW) {
                                        stringResource(R.string.report_filter_slow)
                                    } else {
                                        label
                                    }
                                )
                            }
                        )
                    }
                }
            }
            val refresh = items.loadState.refresh
            if (refresh is LoadState.Error) {
                item(contentType = "refresh-error") {
                    PagingRefreshErrorItem(items)
                }
            }
            if (refresh is LoadState.NotLoading && items.itemCount == 0) {
                item(contentType = "empty") {
                    Text(
                        "No ${filterValue.lowercase()} questions",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.result.questionId },
                    contentType = { "result" }
                ) { index ->
                    val numbered = items[index] ?: return@items
                    ResultCard(
                        index = numbered.index + 1,
                        result = numbered.result,
                        bookmarked = numbered.result.questionId in bookmarked,
                        onToggleBookmark = { viewModel.toggleBookmark(numbered.result.questionId) },
                        showDwell = showDwell,
                        lifetime = report?.lifetime?.get(numbered.result.questionId),
                        review = report?.reviews?.get(numbered.result.questionId)
                    )
                }
            }
            if (items.loadState.append is LoadState.Loading) {
                item(key = "results-load-more", contentType = "load-more") {
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
            if (items.loadState.append is LoadState.Error) {
                item(key = "results-append-error", contentType = "append-error") {
                    PagingAppendErrorItem(items)
                }
            }
        }
    }
}

@Composable
private fun ReportHeaderCard(report: TestReport) {
    val attempt = report.attempt
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(attempt.title, style = MaterialTheme.typography.titleMedium)
            Text(
                SimpleDateFormat("dd MMM yyyy, HH:mm", LocalConfiguration.current.locales[0])
                    .format(Date(attempt.finishedAt)),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                headerLine(report),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            if (report.family == ReportFamily.LEGACY) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.report_legacy_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            // The exam score and the percentage answer different questions
            // (section 11.4): keep the raw marks visible, not only the percent.
            Text(
                stringResource(R.string.l_1f_0f).format(attempt.score, attempt.maxScore),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.report_score_line, attempt.percentage.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (attempt.percentage / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Stat("Correct", report.score.correct.toString(), MaterialTheme.colorScheme.primary)
                Stat("Wrong", report.score.wrong.toString(), MaterialTheme.colorScheme.error)
                Stat("Skipped", report.score.skipped.toString(), MaterialTheme.colorScheme.outline)
                if (report.score.ungraded > 0) {
                    Stat(
                        "Ungraded",
                        report.score.ungraded.toString(),
                        MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.time_taken_02d_02d)
                    .format(attempt.durationSeconds / 60, attempt.durationSeconds % 60),
                style = MaterialTheme.typography.labelMedium
            )
            if (report.requestedCount > 0 && report.shownCount != report.requestedCount) {
                Text(
                    stringResource(
                        R.string.test_asked_shown,
                        report.requestedCount,
                        report.shownCount
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.report_study_unchanged),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StoredHeaderCard(attempt: com.mcqapp.domain.Attempt) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(attempt.title, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.l_1f_0f).format(attempt.score, attempt.maxScore),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.report_score_line, attempt.percentage.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Stat("Correct", attempt.correctCount.toString(), MaterialTheme.colorScheme.primary)
                Stat("Wrong", attempt.wrongCount.toString(), MaterialTheme.colorScheme.error)
                Stat("Skipped", attempt.skippedCount.toString(), MaterialTheme.colorScheme.outline)
            }
            Text(
                stringResource(R.string.report_study_unchanged),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MeaningCard(report: TestReport) {
    val score = report.score
    ReportSection(R.string.report_meaning_title) {
        Text(
            stringResource(
                R.string.report_answered_line,
                score.answered,
                score.shown,
                score.correct,
                score.wrong,
                score.skipped
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        if (score.hasAccuracy) {
            Text(
                stringResource(
                    R.string.report_accuracy_line,
                    (score.accuracy * 100).roundToInt(),
                    score.correct,
                    score.accuracySample
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val marking = when {
            report.negativeMarking > 0 -> stringResource(
                R.string.report_marking_note,
                markingLabel(report.negativeMarking)
            )
            else -> stringResource(R.string.report_no_marking_note)
        }
        Text(
            stringResource(
                R.string.report_score_line,
                score.percentage.roundToInt()
            ) + " · " + marking,
            style = MaterialTheme.typography.bodyMedium
        )
        val margin = report.marginPoints
        if (margin != null) {
            Text(
                stringResource(
                    R.string.report_margin_line,
                    margin.roundToInt(),
                    score.shown
                ),
                style = MaterialTheme.typography.bodySmall
            )
            val previous = report.previousComparable
            if (previous != null) {
                Text(
                    stringResource(
                        R.string.report_previous_comparable,
                        previous.percentage.roundToInt(),
                        previous.marginPoints?.roundToInt() ?: 0,
                        score.percentage.roundToInt()
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                val delta = score.percentage - previous.percentage
                Text(
                    stringResource(
                        R.string.report_delta,
                        if (delta >= 0) "+" else "",
                        delta.roundToInt(),
                        stringResource(
                            if (abs(delta) <= margin) R.string.report_delta_inside
                            else R.string.report_delta_outside
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (report.comparable) {
                Text(
                    stringResource(
                        R.string.report_first_comparable,
                        score.shown,
                        testTypeLabel(report.type)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!report.trendWorthy) {
                Text(
                    stringResource(R.string.report_not_trend_worthy, score.shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ErrorMixCard(report: TestReport) {
    val mix = report.errorMix
    ReportSection(R.string.report_error_mix_title) {
        if (mix.knowledgeGap > 0) {
            MixRow(stringResource(R.string.report_error_knowledge_gap), mix.knowledgeGap)
        }
        if (mix.rushed > 0) MixRow(stringResource(R.string.report_error_rushed), mix.rushed)
        if (mix.skipped > 0) MixRow(stringResource(R.string.report_error_skipped), mix.skipped)
        if (mix.ungraded > 0) MixRow(stringResource(R.string.report_error_ungraded), mix.ungraded)
        if (mix.possibleGuess > 0) {
            MixRow(stringResource(R.string.report_error_guess), mix.possibleGuess)
        }
        if (mix.smallSample) {
            Text(
                stringResource(R.string.report_error_small_sample),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TopicsCard(report: TestReport) {
    ReportSection(R.string.report_topics_title) {
        report.topics.forEach { topic ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    topic.title.ifBlank { "Uncategorized" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (topic.smallSample) {
                    Text(
                        stringResource(R.string.report_topic_small),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Text(
                    stringResource(
                        R.string.report_topic_row,
                        topic.correct,
                        topic.asked,
                        (topic.accuracy * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            LinearProgressIndicator(
                progress = { topic.accuracy.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (report.notSampledTopics.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.report_topics_not_sampled,
                    report.notSampledTopics.size,
                    report.notSampledTopics.joinToString(", ")
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (report.unresolvedResults > 0) {
            Text(
                stringResource(R.string.report_topics_unresolved, report.unresolvedResults),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TimingCard(report: TestReport) {
    val timing = report.timing
    ReportSection(R.string.report_time_title) {
        Text(
            stringResource(
                R.string.report_time_total,
                Dwell.format(timing.totalSeconds),
                Dwell.format(timing.medianSeconds),
                Dwell.format(timing.targetSecondsPerQuestion.toLong())
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        if (timing.wrongAverageSeconds > 0 && timing.correctAverageSeconds > 0) {
            Text(
                stringResource(
                    R.string.report_time_pace,
                    Dwell.format(timing.wrongAverageSeconds),
                    Dwell.format(timing.correctAverageSeconds)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (timing.slowest.isNotEmpty()) {
            val sinks = timing.slowest.map {
                stringResource(
                    R.string.report_time_sink_item,
                    it.index,
                    Dwell.format(it.seconds)
                )
            }
            Text(
                stringResource(R.string.report_time_sinks, sinks.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (timing.smallSample) {
            Text(
                stringResource(R.string.report_time_small_sample),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ActionsCard(
    report: TestReport,
    onStudy: (List<String>) -> Unit,
    onTest: (TestType, Int, List<String>) -> Unit,
    onReview: (String) -> Unit
) {
    ReportSection(R.string.report_actions_title) {
        report.actions.forEachIndexed { index, action ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(
                    "${index + 1}. ${actionTitle(action.rule)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    actionEvidence(report, action.rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        when (action.kind) {
                            com.mcqapp.domain.RecommendedAction.Kind.STUDY ->
                                onStudy(action.categoryIds)
                            com.mcqapp.domain.RecommendedAction.Kind.TEST ->
                                onTest(
                                    action.testType ?: TestType.RANDOM,
                                    action.testCount,
                                    action.categoryIds
                                )
                            com.mcqapp.domain.RecommendedAction.Kind.REVIEW ->
                                onReview(action.filter ?: ReviewFilters.ALL)
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            when (action.kind) {
                                com.mcqapp.domain.RecommendedAction.Kind.STUDY ->
                                    R.string.report_action_study
                                com.mcqapp.domain.RecommendedAction.Kind.TEST ->
                                    R.string.report_action_test
                                com.mcqapp.domain.RecommendedAction.Kind.REVIEW ->
                                    R.string.report_action_review
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun actionTitle(rule: com.mcqapp.domain.RecommendedAction.Rule): String = when (rule) {
    is com.mcqapp.domain.RecommendedAction.Rule.ReviewWrong ->
        stringResource(R.string.report_action_review_wrong, rule.count)
    is com.mcqapp.domain.RecommendedAction.Rule.StudyTopic ->
        stringResource(R.string.report_action_study_topic, rule.topicTitle)
    is com.mcqapp.domain.RecommendedAction.Rule.AttemptEveryQuestion ->
        stringResource(R.string.report_action_attempt_every)
    is com.mcqapp.domain.RecommendedAction.Rule.GuessWhenPossible ->
        stringResource(R.string.report_action_guess)
    is com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock ->
        stringResource(R.string.report_action_longer)
    is com.mcqapp.domain.RecommendedAction.Rule.RepeatPractice ->
        stringResource(R.string.report_action_repeat_practice)
    is com.mcqapp.domain.RecommendedAction.Rule.PractiseMistakes ->
        stringResource(R.string.report_action_practise_mistakes, rule.remaining)
    is com.mcqapp.domain.RecommendedAction.Rule.CheckTransfer ->
        stringResource(R.string.report_action_check_transfer)
    is com.mcqapp.domain.RecommendedAction.Rule.ContinueFocus ->
        stringResource(R.string.report_action_continue_focus, rule.remaining)
    is com.mcqapp.domain.RecommendedAction.Rule.ReviewDue ->
        stringResource(R.string.report_action_review_due, rule.due)
    com.mcqapp.domain.RecommendedAction.Rule.KeepGoing ->
        stringResource(R.string.report_action_keep_going)
}

@Composable
private fun actionEvidence(
    report: TestReport,
    rule: com.mcqapp.domain.RecommendedAction.Rule
): String = when (rule) {
    is com.mcqapp.domain.RecommendedAction.Rule.ReviewWrong ->
        if (rule.slowestSeconds != null) {
            stringResource(
                R.string.report_action_review_wrong_evidence,
                Dwell.format(rule.slowestSeconds)
            )
        } else {
            stringResource(R.string.report_action_review_wrong_evidence_plain, rule.count)
        }
    is com.mcqapp.domain.RecommendedAction.Rule.StudyTopic ->
        stringResource(
            R.string.report_action_study_topic_evidence,
            rule.correct,
            rule.asked,
            (rule.correct.toDouble() / rule.asked * 100).roundToInt(),
            (rule.scopeAccuracyAsked * 100).roundToInt()
        )
    is com.mcqapp.domain.RecommendedAction.Rule.AttemptEveryQuestion ->
        stringResource(R.string.report_action_attempt_every_evidence, rule.skipped)
    is com.mcqapp.domain.RecommendedAction.Rule.GuessWhenPossible ->
        stringResource(R.string.report_action_guess_evidence, rule.skipped)
    is com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock ->
        if (rule.canReachFifty) {
            stringResource(
                R.string.report_action_longer_evidence,
                rule.currentMargin.roundToInt(),
                rule.targetCount,
                rule.targetMargin.roundToInt()
            )
        } else {
            stringResource(
                R.string.report_action_longer_evidence_capped,
                report.scopeSize,
                rule.targetCount,
                rule.targetMargin.roundToInt()
            )
        }
    is com.mcqapp.domain.RecommendedAction.Rule.RepeatPractice ->
        stringResource(
            R.string.report_action_repeat_practice_evidence,
            rule.correct,
            rule.asked,
            (rule.accuracy * 100).roundToInt()
        )
    is com.mcqapp.domain.RecommendedAction.Rule.PractiseMistakes ->
        stringResource(R.string.report_action_practise_mistakes_evidence, rule.remaining)
    is com.mcqapp.domain.RecommendedAction.Rule.CheckTransfer ->
        stringResource(
            R.string.report_action_check_transfer_evidence,
            rule.practiceDeltaPoints.roundToInt()
        )
    is com.mcqapp.domain.RecommendedAction.Rule.ContinueFocus ->
        stringResource(
            R.string.report_action_continue_focus_evidence,
            rule.covered,
            rule.pool
        )
    is com.mcqapp.domain.RecommendedAction.Rule.ReviewDue ->
        stringResource(R.string.report_action_review_due_evidence, rule.due)
    com.mcqapp.domain.RecommendedAction.Rule.KeepGoing ->
        stringResource(R.string.report_action_keep_going_evidence)
}

@Composable
private fun ReportSection(titleRes: Int, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun MixRow(label: String, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun headerLine(report: TestReport): String {
    val length = when {
        report.family == ReportFamily.SHORT ->
            stringResource(R.string.report_short_test) + " · " +
                attemptTypeLine(report.attempt) + " · " +
                stringResource(R.string.report_header_questions, report.shownCount)
        report.family == ReportFamily.LEGACY ->
            stringResource(R.string.report_legacy_attempt) + " · " +
                stringResource(R.string.report_header_questions, report.shownCount)
        report.family == ReportFamily.PRACTICE ->
            stringResource(R.string.report_practice) + " · " +
                attemptTypeLine(report.attempt) + " · " +
                stringResource(R.string.report_header_questions, report.shownCount)
        report.scopeSize > 0 ->
            stringResource(reportKind(report.family)) + " · " +
                attemptTypeLine(report.attempt) + " · " +
                stringResource(
                    R.string.report_header_of_scope,
                    report.shownCount,
                    report.scopeSize
                )
        else ->
            stringResource(reportKind(report.family)) + " · " +
                attemptTypeLine(report.attempt) + " · " +
                stringResource(R.string.report_header_questions, report.shownCount)
    }
    val badge = when {
        report.family == ReportFamily.MOCK -> stringResource(R.string.report_comparable)
        report.family == ReportFamily.FRESH_SAMPLE ->
            stringResource(R.string.report_fresh_comparable)
        report.family == ReportFamily.SHORT -> stringResource(R.string.report_no_margin)
        else -> stringResource(R.string.report_not_comparable)
    }
    return "$length · $badge"
}

private fun reportKind(family: ReportFamily): Int = when (family) {
    ReportFamily.MOCK -> R.string.report_mock
    ReportFamily.FRESH_SAMPLE -> R.string.report_fresh_sample
    ReportFamily.PRACTICE -> R.string.report_practice
    ReportFamily.SHORT -> R.string.report_short_test
    ReportFamily.LEGACY -> R.string.report_legacy_attempt
}

/** The paper's marking as the fraction it usually is: 1/3, 1/4, 1/2 or a percentage. */
private fun markingLabel(value: Double): String = when {
    value <= 0.0 -> ""
    abs(value - 1.0 / 3.0) < 0.02 -> "1/3"
    abs(value - 1.0 / 4.0) < 0.02 -> "1/4"
    abs(value - 1.0 / 2.0) < 0.02 -> "1/2"
    abs(value - 1.0) < 0.02 -> "1"
    else -> "${(value * 100).roundToInt()}%"
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PracticeCard(report: TestReport) {
    val practice = report.practice ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.report_practice_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            if (practice.withoutTargets) {
                Text(
                    stringResource(R.string.report_practice_no_targets),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                practice.targets.forEach { target ->
                    Text(
                        stringResource(
                            R.string.report_practice_target_row,
                            target.title,
                            target.correct,
                            target.asked,
                            (target.accuracy * 100).roundToInt(),
                            target.poolSize
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.report_practice_overall,
                    (practice.overallAccuracy * 100).roundToInt(),
                    practice.overallAsked
                ),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                stringResource(R.string.report_practice_bias),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The practice line is drawn only on a fixed recorded target set;
            // it never appears beside the comparable mock trend.
            report.practiceTrend?.let { trend ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.report_practice_trend_title),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    stringResource(
                        R.string.report_practice_trend_line,
                        trend.points.size,
                        trend.points.joinToString(" → ") {
                            (it.accuracy * 100).roundToInt().toString() + "%"
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.report_practice_trend_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                trend.deltaPoints?.let { delta ->
                    Text(
                        stringResource(
                            R.string.report_practice_trend_delta,
                            delta.roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(report: TestReport) {
    val memory = report.memory ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.report_memory_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            memory.quadrants.forEach { quadrant ->
                val label = when {
                    quadrant.hard && !quadrant.correct -> R.string.report_memory_hard_wrong
                    quadrant.hard && quadrant.correct -> R.string.report_memory_hard_correct
                    !quadrant.hard && !quadrant.correct -> R.string.report_memory_easy_wrong
                    else -> R.string.report_memory_easy_correct
                }
                Text(
                    stringResource(label, quadrant.count),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (memory.noStudy > 0) {
                Text(
                    stringResource(R.string.report_memory_no_study, memory.noStudy),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (memory.dueNow > 0) {
                Text(
                    stringResource(R.string.report_memory_due, memory.dueNow),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            memory.meanRetrievability?.let { mean ->
                Text(
                    stringResource(
                        R.string.report_memory_retrievability,
                        (mean * 100).roundToInt(),
                        memory.validCards
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.report_memory_estimate_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CoverageCard(report: TestReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.report_coverage_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            report.coverage?.let { coverage ->
                val percent = if (coverage.scopeSize > 0) {
                    (coverage.engaged.toDouble() / coverage.scopeSize * 100).roundToInt()
                } else {
                    0
                }
                Text(
                    stringResource(
                        R.string.report_coverage_line,
                        coverage.engaged,
                        coverage.scopeSize,
                        percent,
                        coverage.repeats
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.report_coverage_unseen, coverage.unseenRemaining),
                    style = MaterialTheme.typography.labelSmall
                )
                if (coverage.dueNow > 0) {
                    Text(
                        stringResource(R.string.report_coverage_due, coverage.dueNow),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            report.mistakes?.let { mistakes ->
                Text(
                    stringResource(
                        R.string.report_mistakes_line,
                        mistakes.added,
                        mistakes.cleared,
                        mistakes.remaining
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TrendCard(report: TestReport) {
    val trend = report.trend ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.report_trend_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            val last = trend.points.lastOrNull() ?: return@Column
            val sign = if (trend.deltaPoints >= 0) "+" else "−"
            Text(
                stringResource(
                    R.string.report_trend_line,
                    trend.points.size,
                    last.percentage.roundToInt(),
                    sign,
                    abs(trend.deltaPoints).roundToInt()
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(
                    if (trend.withinNoise) R.string.report_trend_noise else R.string.report_trend_holds
                ),
                style = MaterialTheme.typography.labelSmall
            )
            if (trend.freshSample) {
                Text(
                    stringResource(R.string.report_trend_fresh_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IntervalCard(report: TestReport) {
    val interval = report.interval ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.report_interval_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.report_interval_line,
                    interval.lowPercent.roundToInt(),
                    interval.highPercent.roundToInt()
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(
                    R.string.report_interval_note,
                    interval.passageBlocks,
                    interval.resamples
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultCard(
    index: Int,
    result: QuestionResult,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    showDwell: Boolean = false,
    lifetime: com.mcqapp.domain.LifetimeAccuracy? = null,
    review: QuestionReview? = null
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
            if (review != null) {
                // "Why this matters" for this question, then the Study badge.
                // Both read the same evidence the report cards already used.
                Text(
                    stringResource(reviewTagLabel(review.tag)),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (review.tag) {
                        ReviewTag.SOLID_CORRECT -> MaterialTheme.colorScheme.primary
                        ReviewTag.SKIPPED, ReviewTag.UNGRADED ->
                            MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    reviewStudyLine(review),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (review.targeted) {
                    Text(
                        stringResource(R.string.report_targeted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (lifetime != null) {
                Text(
                    stringResource(
                        R.string.report_lifetime_line,
                        lifetime.correct,
                        lifetime.answered,
                        lifetime.percentage.roundToInt(),
                        stringResource(
                            if (lifetime.counterSet == com.mcqapp.domain.signals.CounterSet.REPRESENTATIVE) {
                                R.string.report_lifetime_mock
                            } else {
                                R.string.report_lifetime_all
                            }
                        )
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    "Time spent: ${Dwell.format(result.dwellSeconds)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            QuestionImage(src = result.explanationImage, contentDescription = stringResource(R.string.explanation_image))
        }
    }
}

/** The "why this matters" label for one reviewed question. */
@Composable
private fun reviewTagLabel(tag: ReviewTag): Int = when (tag) {
    ReviewTag.KNOWLEDGE_GAP -> R.string.report_tag_knowledge_gap
    ReviewTag.RUSHED -> R.string.report_tag_rushed
    ReviewTag.SKIPPED -> R.string.report_tag_skipped
    ReviewTag.POSSIBLE_GUESS -> R.string.report_tag_possible_guess
    ReviewTag.FRAGILE_CORRECT -> R.string.report_tag_fragile_correct
    ReviewTag.SOLID_CORRECT -> R.string.report_tag_solid_correct
    ReviewTag.UNGRADED -> R.string.report_tag_ungraded
}

/**
 * The question's Study badge: a valid card gives difficulty and predicted
 * recall (plus due-now), a new or stale card says which it is, and a question
 * with no card says so rather than implying a memory estimate.
 */
@Composable
private fun reviewStudyLine(review: QuestionReview): String {
    // Built by arithmetic, not locale formatting, so the value is stable and
    // does not read a non-observable Locale inside composition.
    val difficulty = ((review.difficulty ?: 0.0) * 10).roundToInt() / 10.0
    val recall = ((review.retention ?: 0.0) * 100).roundToInt()
    return when {
        review.inStudy && review.dueNow ->
            stringResource(R.string.report_study_line_due, difficulty, recall)
        review.inStudy ->
            stringResource(R.string.report_study_line, difficulty, recall)
        review.staleCard -> stringResource(R.string.report_study_stale)
        review.newCard -> stringResource(R.string.report_study_new)
        else -> stringResource(R.string.report_no_study)
    }
}
