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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.TestType
import com.mcqapp.domain.Trends
import com.mcqapp.ui.common.UnfinishedTestCard
import com.mcqapp.ui.common.unfinishedTestRoute
import com.mcqapp.ui.common.attemptFamilyLabel
import com.mcqapp.ui.common.attemptTypeLine
import com.mcqapp.ui.common.testTypeLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val statsState by viewModel.statsState.collectAsStateWithLifecycle()
    val unfinishedTest by viewModel.unfinishedTest.collectAsStateWithLifecycle()

    // R10: resolve the ticked category ids of scoped trends to their titles so
    // the user never sees opaque database ids in a trend label.
    val trends = remember(attempts) { Trends.perFamily(attempts) }
    var categoryTitles by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(trends) {
        val ids = trends.asSequence()
            .flatMap { it.scopeCategoryIds.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (ids.isNotEmpty()) {
            categoryTitles = repository.db().categoryDao()
                .getByIdsChunked(ids)
                .associate { it.id to it.title }
        }
    }

    // Reviews logged elsewhere (a study session) must show up when coming
    // back: this screen's top-lists are read on demand, not observed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("stats") }) {
                        Icon(
                            Icons.Default.QueryStats,
                            contentDescription = stringResource(R.string.stats)
                        )
                    }
                }
            )
        }
    ) { padding ->
        // R6: a failed top-list read is no longer silent — say so and let the
        // learner retry in place instead of showing a stale/empty list.
        statsState.error?.let { error ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.refresh() }) {
                    Text(stringResource(R.string.retry))
                }
            }
            return@Scaffold
        }
        if (attempts.isEmpty() && unfinishedTest == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_attempts_yet))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (unfinishedTest != null) {
                    item(contentType = "unfinished-test") {
                        UnfinishedTestCard(
                            saved = unfinishedTest!!,
                            onResume = { navController.navigate(unfinishedTestRoute(unfinishedTest!!)) }
                        )
                    }
                }
                if (attempts.isEmpty()) {
                    item(contentType = "no-attempts") {
                        Text(stringResource(R.string.no_attempts_yet))
                    }
                }
                if (statsState.loading) {
                    item(contentType = "stats-loading") {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (trends.isNotEmpty()) {
                    item(contentType = "header") {
                        Text(stringResource(R.string.trends), style = MaterialTheme.typography.titleSmall)
                    }
                    items(trends, key = { "trend-${it.key}" }, contentType = { "trend" }) { trend ->
                        TrendCard(trend = trend, categoryTitles = categoryTitles)
                    }
                }
                if (weakest.isNotEmpty()) {
                    item(contentType = "header") {
                        Text(stringResource(R.string.weakest_categories), style = MaterialTheme.typography.titleSmall)
                    }
                    items(weakest, key = { "weak-${it.paperId}-${it.categoryTitle}" }, contentType = { "weakest" }) { mastery ->
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
                                    stringResource(
                                        R.string.history_mastery_line,
                                        mastery.paperTitle, formatPercent(mastery.rate * 100),
                                        mastery.correct, mastery.graded
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                // R5: resolve the spoken label in composable
                                // scope — the semantics lambda is not
                                // @Composable, so stringResource cannot be
                                // called inside it.
                                val masteryProgressLabel = stringResource(
                                    R.string.r2_res_mastery_progress,
                                    formatPercent(mastery.rate * 100)
                                )
                                LinearProgressIndicator(
                                    progress = { mastery.rate.toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics {
                                            contentDescription = masteryProgressLabel
                                        }
                                )
                            }
                        }
                    }
                }
                if (hardest.isNotEmpty()) {
                    item(contentType = "header") {
                        Text(stringResource(R.string.hardest_questions), style = MaterialTheme.typography.titleSmall)
                    }
                    items(hardest, key = { "hard-${it.questionId}" }, contentType = { "hardest" }) { stat ->
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
                                    stringResource(
                                        R.string.history_wrong_skipped_line,
                                        formatPercent(stat.wrongRate * 100),
                                        formatPercent(stat.skipRate * 100), stat.gradedAttempts
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                // The list always carries its own heading: with only legacy
                // attempts there are no trends, and the rows used to appear
                // with no label above them.
                item(contentType = "header") {
                    Text(stringResource(R.string.attempts), style = MaterialTheme.typography.titleSmall)
                }
                items(attempts, key = { it.id }, contentType = { "attempt" }) { attempt ->
                    // R5: a labelled clickable gives TalkBack a real button
                    // role and an action label instead of an unlabelled tap
                    // target (this Material3 Card overload has no onClickLabel
                    // parameter, so the semantics live on the modifier).
                    val openAttemptLabel = stringResource(R.string.r2_res_open_attempt)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClickLabel = openAttemptLabel,
                                role = Role.Button
                            ) {
                                navController.navigate(
                                    com.mcqapp.ui.navigation.resultsRoute(attempt.id)
                                )
                            }
                            .semantics {
                                contentDescription = openAttemptLabel
                                stateDescription = attempt.title
                            }
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
                                    attemptFamilyLabel(attempt) + " · " + attemptTypeLine(attempt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", LocalConfiguration.current.locales[0])
                                        .format(Date(attempt.finishedAt)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatPercent(attempt.percentage),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(
                                        R.string.history_correct_line,
                                        attempt.correctCount, attempt.totalQuestions
                                    ),
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
private fun TrendCard(
    trend: Trends.FamilyTrend,
    categoryTitles: Map<String, String> = emptyMap()
) {
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
                    buildString {
                        append(com.mcqapp.ui.common.familyLabel(trend.family))
                        trend.type?.let { append(" · ").append(testTypeLabel(it)) }
                        if (trend.requestedCount > 0) {
                            append(" · ").append(
                                pluralStringResource(
                                    R.plurals.test_setup_pool_questions,
                                    trend.requestedCount, trend.requestedCount
                                )
                            )
                        }
                        // Two lines with the same title/type are still distinct:
                        // name the exact scope and the custom choice they cover.
                        // R10: a custom scope names its category titles (with an
                        // id only as a fallback), never opaque database ids.
                        val scopeSuffix = trend.scopeSuffix(categoryTitles)
                        append(" · ").append(
                            when {
                                trend.isWholePaper -> stringResource(R.string.stats_trend_scope_whole)
                                scopeSuffix == null -> stringResource(R.string.stats_trend_scope_topics)
                                else -> stringResource(R.string.stats_trend_scope_topics_detail, scopeSuffix)
                            }
                        )
                        trend.variant?.let { append(" · ").append(it) }
                        if (trend.freshSample) {
                            append(" · ").append(stringResource(R.string.report_fresh_sample))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    stringResource(
                        R.string.history_trend_attempts_best,
                        pluralStringResource(
                            R.plurals.attempts_count, trend.attempts, trend.attempts
                        ),
                        formatPercent(trend.bestPercent)
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatPercent(trend.latestPercent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (trend.attempts > 1) {
                    // R5: the arrow + colour alone said nothing to TalkBack.
                    // The change is latest minus FIRST (not the result page's
                    // latest minus previous), so the copy says "since first
                    // attempt" outright. A change inside the two endpoints'
                    // combined margin is not a direction at all: no arrow.
                    val direction = if (trend.withinNoise) {
                        Trends.Delta.UNCHANGED
                    } else {
                        Trends.deltaDirection(trend.deltaPoints)
                    }
                    val (arrow, color) = when (direction) {
                        Trends.Delta.IMPROVED -> "▲" to verdictColors().rising
                        Trends.Delta.DECLINED -> "▼" to verdictColors().falling
                        Trends.Delta.UNCHANGED -> "=" to Color.Gray
                    }
                    val points = String.format(
                        Locale.ROOT, "%.0f", kotlin.math.abs(trend.deltaPoints)
                    )
                    val sign = if (trend.deltaPoints >= 0) "+" else "−"
                    val shown = if (trend.withinNoise) {
                        stringResource(R.string.test_ux_history_delta_within)
                    } else {
                        stringResource(R.string.test_ux_history_delta_line, sign, points)
                    }
                    val spoken = if (trend.withinNoise) {
                        stringResource(R.string.test_ux_history_delta_spoken_unchanged)
                    } else {
                        when (direction) {
                            Trends.Delta.IMPROVED ->
                                stringResource(R.string.test_ux_history_delta_spoken_improved, points)
                            Trends.Delta.DECLINED ->
                                stringResource(R.string.test_ux_history_delta_spoken_declined, points)
                            Trends.Delta.UNCHANGED ->
                                stringResource(R.string.test_ux_history_delta_spoken_unchanged)
                        }
                    }
                    Text(
                        if (trend.withinNoise) shown else "$arrow $shown",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier
                            .testTag("history-trend-delta")
                            .semantics { contentDescription = spoken }
                    )
                }
            }
        }
    }
}


/**
 * ASCII digits and a dot decimal regardless of the device locale: the history
 * rows sit beside chart labels that already use Locale.ENGLISH, so a German
 * or Devanagari default here would mix digit systems on one screen.
 */
internal fun formatPercent(value: Double): String =
    String.format(Locale.ROOT, "%.0f%%", value)
