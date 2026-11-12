package com.mcqapp.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.R
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.StudySession
import com.mcqapp.ui.common.StatsRangeChips
import com.mcqapp.ui.stats.charts.formatDuration
import com.mcqapp.ui.stats.charts.formatPct
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * The sidebar history: every study sitting in the selected range, grouped by
 * day, with its questions, time, grades and topics. The statistics page keeps
 * the aggregate charts; this screen is the per-sitting record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: SessionsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A session finished elsewhere must show up when coming back; the
    // ViewModel lives as long as the backstack entry does.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StatsRangeChips(selected = state.range, onSelect = viewModel::setRange)
            when {
                state.error != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.sessions_load_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = viewModel::refresh) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                state.data == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                else -> state.data?.let { SessionsList(it) }
            }
        }
    }
}

@Composable
private fun SessionsList(data: SessionsData) {
    val groups = remember(data) {
        data.sessions.groupBy { it.day }.entries.sortedByDescending { it.key }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("sessions-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "summary") { SummaryCard(data) }
        if (data.totalSessions == 0) {
            item(contentType = "empty") {
                Text(
                    stringResource(R.string.sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        groups.forEach { (day, sessions) ->
            item(key = "day-$day", contentType = "day") {
                DayHeader(day = day, sessions = sessions.size, todayDay = data.todayDay)
            }
            itemsIndexed(
                items = sessions,
                key = { index, session -> "session-$day-$index-${session.startedAt}" },
                contentType = { _, _ -> "session" }
            ) { _, session ->
                SessionCard(session)
            }
        }
    }
}

@Composable
private fun SummaryCard(data: SessionsData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                pluralStringResource(
                    R.plurals.sessions_count, data.totalSessions, data.totalSessions
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(
                    R.string.sessions_summary,
                    data.totalReviews,
                    formatDuration(data.totalSeconds.toDouble())
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.sessions_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DayHeader(day: Long, sessions: Int, todayDay: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val label = when (day) {
        todayDay -> stringResource(R.string.sessions_today)
        todayDay - 1 -> stringResource(R.string.sessions_yesterday)
        else -> LocalDate.ofEpochDay(day)
            .format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            pluralStringResource(R.plurals.sessions_count, sessions, sessions),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionCard(session: StudySession) {
    var expanded by rememberSaveable(session.startedAt, session.endedAt) {
        mutableStateOf(false)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.sessions_clock,
                        clock(session.startedAt),
                        clock(session.endedAt)
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    formatDuration(session.durationSeconds.toDouble()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            val questions = pluralStringResource(
                R.plurals.sessions_questions, session.questions, session.questions
            )
            val reviews = if (session.reviews != session.questions) {
                " · " + pluralStringResource(
                    R.plurals.sessions_reviews, session.reviews, session.reviews
                )
            } else {
                ""
            }
            Text(
                stringResource(
                    R.string.sessions_line,
                    questions + reviews,
                    session.accuracy?.let { formatPct(it) } ?: "—",
                    formatDuration(session.timedSeconds.toDouble())
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(
                    R.string.sessions_grades,
                    session.again, session.hard, session.good, session.easy
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (session.papers.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.sessions_papers,
                        session.papers.joinToString(" · ") { "${it.label} ${it.reviews}" }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (session.categories.isNotEmpty()) {
                val shown = if (expanded) session.categories else session.categories.take(TOPICS_COLLAPSED)
                val remaining = session.categories.size - shown.size
                val topics = shown.joinToString(" · ") { "${it.label} ${it.reviews}" } +
                    if (remaining > 0) {
                        " · " + stringResource(R.string.sessions_more_topics, remaining)
                    } else {
                        ""
                    }
                Text(
                    stringResource(R.string.sessions_topics, topics),
                    style = MaterialTheme.typography.labelSmall
                )
                if (session.categories.size > TOPICS_COLLAPSED) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            stringResource(
                                if (expanded) R.string.sessions_show_less
                                else R.string.sessions_show_all_topics
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun clock(ms: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Topic chips shown before the "show all" action appears. */
private const val TOPICS_COLLAPSED = 4
