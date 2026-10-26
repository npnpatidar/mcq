package com.mcqapp.ui.test

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcqapp.ui.theme.verdictColors
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.app.Application
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.AnswerReview
import com.mcqapp.domain.Dwell
import com.mcqapp.domain.ExamMode
import com.mcqapp.domain.Feedback
import com.mcqapp.domain.Question
import com.mcqapp.domain.SubmitSummary
import com.mcqapp.ui.TestViewModelFactory
import com.mcqapp.util.Logger
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestSessionScreen(
    repository: McqRepository,
    paperId: String,
    categoryIds: List<String>,
    mistakesOnly: Boolean = false,
    drillCount: Int = 0,
    drillMinutes: Int = 0,
    durationMinutes: Int = com.mcqapp.domain.ExamTiming.USE_PAPER_DURATION,
    navController: NavController
) {
    val context = LocalContext.current
    val viewModel: TestViewModel = viewModel(
        key = "test-$paperId-${categoryIds.joinToString(",")}-mistakes=$mistakesOnly" +
            "-drill=$drillCount/$drillMinutes-minutes=$durationMinutes",
        factory = TestViewModelFactory(
            context.applicationContext as Application,
            paperId,
            categoryIds,
            mistakesOnly,
            drillCount,
            drillMinutes,
            durationMinutes
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveDwell by viewModel.liveDwell.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.attemptId) {
        state.attemptId?.let { id ->
            navController.navigate(com.mcqapp.ui.navigation.resultsRoute(id)) {
                popUpTo("library") { inclusive = false }
            }
        }
    }

    // Saveable, not remember: a rotation mid-exam must not resurrect dealt-with
    // state. The ungraded dialog reappeared on every rotation even after
    // Continue was chosen (and its being open re-paused dwell each time).
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showSubmitDialog by rememberSaveable { mutableStateOf(false) }
    var ungradedDismissed by rememberSaveable { mutableStateOf(false) }
    val ungradedTotal = state.ungradedTotal
    val resumeOffer = state.resumeOffer
    if (resumeOffer != null) {
        val answered = resumeOffer.selections.count { it.value.isNotEmpty() }
        val minutes = resumeOffer.remainingSeconds / 60
        val seconds = resumeOffer.remainingSeconds % 60
        AlertDialog(
            onDismissRequest = { /* explicit choice required */ },
            title = { Text(stringResource(R.string.resume_previous_attempt)) },
            text = {
                Text(
                    if (resumeOffer.totalSeconds > 0) {
                        pluralStringResource(
                            R.plurals.resume_saved_with_time_text,
                            answered,
                            answered,
                            minutes,
                            seconds
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.resume_saved_text,
                            answered,
                            answered
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resume() }) { Text(stringResource(R.string.resume)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.discardResume() }) { Text(stringResource(R.string.start_fresh)) }
            }
        )
    }
    if (!state.loading && ungradedTotal > 0 && !ungradedDismissed) {
        AlertDialog(
            onDismissRequest = { ungradedDismissed = true },
            title = { Text(stringResource(R.string.questions_without_an_answer_key)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.ungraded_practice_note,
                        ungradedTotal,
                        ungradedTotal
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { ungradedDismissed = true }) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        ungradedDismissed = true
                        navController.popBackStack()
                    }
                ) { Text(stringResource(R.string.go_back)) }
            }
        )
    }

    val ungradedBlocking = !state.loading && ungradedTotal > 0 && !ungradedDismissed
    val questionVisible =
        !state.loading &&
            state.loadError == null &&
            state.currentQuestion != null &&
            !showPalette &&
            !showSubmitDialog &&
            !state.reviewOpen &&
            resumeOffer == null &&
            !ungradedBlocking
    LaunchedEffect(questionVisible) {
        viewModel.setQuestionVisible(questionVisible)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.paper?.title ?: stringResource(R.string.test),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.totalSeconds > 0) {
                        val minutes = state.remainingSeconds / 60
                        val seconds = state.remainingSeconds % 60
                        val urgent = state.remainingSeconds <= 60
                        // Was a focusable, unlabelled IconButton that did
                        // nothing; a plain Row cannot be mistaken for a control.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = stringResource(R.string.time_remaining),
                                tint = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.l_02d_02d).format(minutes, seconds),
                                color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    val question = state.currentQuestion
                    if (question != null) {
                        if (ExamMode.canFlag(state.strictMode)) {
                            // The result is being saved (or already is): the real
                        // guard lives in the view model, this only keeps the
                        // control from promising otherwise.
                        IconButton(
                            onClick = { viewModel.toggleFlag() },
                            enabled = !(state.saving || state.submitted)
                        ) {
                                Icon(
                                    Icons.Default.Flag,
                                    contentDescription = stringResource(R.string.flag_question),
                                    tint = if (question.id in state.flagged) verdictColors().tricky else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.toggleBookmarkCurrent() }) {
                            Icon(
                                if (question.id in state.bookmarked) Icons.Default.Bookmark
                                else Icons.Default.BookmarkBorder,
                                contentDescription = stringResource(R.string.bookmark_question),
                                tint = if (question.id in state.bookmarked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.loading))
            }
            return@Scaffold
        }

        state.loadError?.let { error ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.retry() }) { Text(stringResource(R.string.retry)) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { navController.popBackStack() }) { Text(stringResource(R.string.go_back)) }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showPalette) {
                QuestionPalette(
                    state = state,
                    onPick = { index ->
                        viewModel.goTo(index)
                        showPalette = false
                    }
                )
            }

            state.timeWarning?.let { warning ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.dismissTimeWarning() }) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                }
            }

            state.saveError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        // The snapshot is still on disk, so the only thing
                        // standing between the user and their result is this.
                        TextButton(
                            onClick = {
                                viewModel.dismissSaveError()
                                viewModel.submit()
                            },
                            enabled = !state.saving
                        ) {
                            Text(if (state.saving) stringResource(R.string.saving) else stringResource(R.string.retry))
                        }
                    }
                }
            }

            // A large paper writes its result rows in batches; show how far it
            // got instead of an indeterminate spinner that can sit for minutes.
            if (state.saving && state.saveTotal > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.saving_progress_text, state.saveDone, state.saveTotal),
                            style = MaterialTheme.typography.labelMedium
                        )
                        LinearProgressIndicator(
                            progress = { state.saveDone.toFloat() / state.saveTotal },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    }
                }
            }

            val question = state.currentQuestion
            if (question == null) {
                // An empty order really is empty; a null card over a non-empty
                // order is the first resolve still in flight — `loading` is
                // already false here, so showing the empty text flashed for a
                // frame at session start (consolidated_bugs.md F19).
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.questionCount == 0) {
                        Text(stringResource(R.string.no_questions_in_this_selection))
                    } else {
                        CircularProgressIndicator()
                    }
                }
                return@Scaffold
            }

            // The question body scrolls; the scroll state must not outlive the
            // question it belongs to, or question N+1 opens at question N's
            // offset (consolidated_bugs.md F8).
            val scrollState = rememberScrollState()
            LaunchedEffect(question.id) { scrollState.scrollTo(0) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                // Own composable, and it reads the dwell clock from its own
                // flow: a read of `state` here would put the per-second tick
                // in this scope and re-execute the whole question body below,
                // including every image.
                QuestionHeader(
                    index = state.currentIndex,
                    total = state.questionCount,
                    marks = question.marks,
                    liveDwell = viewModel.liveDwell,
                    dwellActive = state.dwellActive,
                    onToggleDwellPause = { viewModel.toggleDwellPause() }
                )
                val practice = ExamMode.effectivePractice(state.practiceMode, state.strictMode)
                if (practice) {
                    Text(
                        stringResource(R.string.practice_answers_shown_instantly),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (state.strictMode) {
                    Text(
                        stringResource(R.string.strict_exam_aids_hidden_until_submit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (state.mistakesOnly) {
                    Text(
                        stringResource(R.string.mistakes_round_previously_missed_questions),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (state.isDrill) {
                    Text(
                        stringResource(R.string.drill_banner_text, state.questionCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (state.drillRequested > 0 && state.questionCount > state.drillRequested) {
                        // A passage block crossed the requested cut (D1):
                        // whole blocks are shown rather than splitting one.
                        Text(
                            stringResource(
                                R.string.drill_overshoot_shown,
                                state.drillRequested,
                                state.questionCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                if (question.isMultiCorrect) {
                    Text(
                        stringResource(R.string.multiple_correct_select_all_that_apply),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (question.correctOptionIds.isEmpty()) {
                    Text(
                        stringResource(R.string.no_answer_key_not_scored),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.height(8.dp))
                // The passage the question reads from. Repeated on every member
                // rather than sticky: the question column scrolls as one, so the
                // context is always above the stem (D8 philosophy — each question
                // stays self-contained).
                state.currentPassage?.let { passage ->
                    PassageCard(passage = passage)
                    Spacer(Modifier.height(8.dp))
                }
                ContentElements(question.elements, textStyle = MaterialTheme.typography.titleMedium)
                QuestionImage(
                    src = question.image,
                    contentDescription = stringResource(R.string.question_image),
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(16.dp))
                val questionUngraded = question.correctOptionIds.isEmpty()
                val manuallyRevealed = question.id in state.revealed
                question.options.forEach { option ->
                    val selected = option.id in (state.selections[question.id] ?: emptySet())
                    val revealed = Feedback.liveReveal(
                        practice, manuallyRevealed, questionUngraded
                    )
                    val isCorrectOption = option.id in question.correctOptionIds
                    OptionRow(
                        text = option.text,
                        elements = option.elements,
                        image = option.image,
                        selected = selected,
                        revealed = revealed,
                        isCorrectOption = isCorrectOption,
                        multi = question.isMultiCorrect,
                        // Saving the scored attempt: no more edits, visual as
                        // well as logical (the view model refuses them too).
                        enabled = !(state.saving || state.submitted),
                        onClick = { viewModel.toggleOption(option.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (Feedback.showExplanation(practice, manuallyRevealed)) {
                    Spacer(Modifier.height(8.dp))
                    ExplanationCard(question = question)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { viewModel.previous() },
                    enabled = !(state.saving || state.submitted) && state.currentIndex > 0
                ) {
                    Text(stringResource(R.string.previous))
                }
                if (ExamMode.canOpenPalette(state.strictMode)) {
                    TextButton(onClick = { showPalette = !showPalette }) {
                        Text(stringResource(R.string.answered_progress_text, state.answeredCount, state.questionCount))
                    }
                } else {
                    Text(
                        stringResource(R.string.position_progress_text, state.currentIndex + 1, state.questionCount),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (state.currentIndex < state.questionCount - 1) {
                    Button(
                        onClick = { viewModel.next() },
                        enabled = !(state.saving || state.submitted)
                    ) { Text(stringResource(R.string.next)) }
                } else {
                    Button(onClick = { showSubmitDialog = true }) { Text(stringResource(R.string.submit)) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (ExamMode.canReveal(state.practiceMode, state.strictMode)) {
                    OutlinedButton(
                        onClick = { viewModel.revealCurrent() },
                        enabled = question.id !in state.revealed,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.show_answer))
                    }
                }
                Button(
                    onClick = { showSubmitDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.submit_test_2))
                }
            }
        }
    }

    if (showSubmitDialog) {
        // The on-screen question's dwell lives in its own flow now (the
        // per-second tick no longer copies the whole map), so fold it in before
        // picking the slowest — otherwise the question being answered right now
        // could never win.
        val currentId = state.currentQuestion?.id
        val dwell = if (currentId != null && liveDwell > (state.dwellSeconds[currentId] ?: 0L)) {
            state.dwellSeconds + (currentId to liveDwell)
        } else {
            state.dwellSeconds
        }
        val slowest = dwell.maxByOrNull { it.value }
        val slowestIndex = slowest?.let { entry ->
            state.order.indexOfId(entry.key).takeIf { it >= 0 }
        }
        val summary = SubmitSummary.Summary(
            answered = state.answeredCount,
            total = state.questionCount,
            flagged = state.flagged.size,
            ungraded = ungradedTotal,
            slowestQuestion = slowestIndex?.plus(1),
            slowestSeconds = slowest?.value ?: 0L
        )
        val firstFlagged = state.order.firstIndexOfAny(state.flagged)
        AlertDialog(
            onDismissRequest = { showSubmitDialog = false },
            title = { Text(stringResource(R.string.submit_test)) },
            text = {
                Column {
                    // Each summary line through its own resource, so the
                    // dialog is localisable (SubmitSummary.parts is the order).
                    SubmitSummary.parts(summary).forEach { line ->
                        val text = when (line) {
                            is SubmitSummary.Line.Answered -> stringResource(
                                R.string.submit_answered_line, line.answered, line.total
                            )
                            is SubmitSummary.Line.Unanswered -> stringResource(
                                R.string.submit_unanswered_line, line.unanswered
                            )
                            is SubmitSummary.Line.Flagged -> stringResource(
                                R.string.submit_flagged_line, line.count
                            )
                            is SubmitSummary.Line.Ungraded -> stringResource(
                                R.string.submit_ungraded_line, line.count
                            )
                            is SubmitSummary.Line.Slowest -> stringResource(
                                R.string.submit_slowest_line,
                                line.question,
                                Dwell.format(line.seconds)
                            )
                        }
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = {
                        showSubmitDialog = false
                        viewModel.openAnswerReview()
                    }) {
                        Text(stringResource(R.string.review_answers))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSubmitDialog = false
                        viewModel.submit()
                    },
                    modifier = Modifier.testTag("confirm-submit")
                ) { Text(stringResource(R.string.submit)) }
            },
            dismissButton = {
                if (firstFlagged >= 0) {
                    TextButton(onClick = {
                        showSubmitDialog = false
                        viewModel.goTo(firstFlagged)
                    }) { Text(stringResource(R.string.review_flagged)) }
                } else {
                    TextButton(onClick = { showSubmitDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    if (state.reviewOpen) {
        // Rows page over the session order: only the visible slice's question
        // content is resolved, so a huge paper never builds every row.
        val reviewItems = viewModel.reviewPagingData.collectAsLazyPagingItems()
        AlertDialog(
            onDismissRequest = { viewModel.dismissAnswerReview() },
            title = { Text(stringResource(R.string.your_answers)) },
            text = {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(
                        count = reviewItems.itemCount,
                        key = reviewItems.itemKey { it.questionId }
                    ) { index ->
                        val row = reviewItems[index] ?: return@items
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.dismissAnswerReview()
                                    viewModel.goTo(row.number - 1)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.review_row_number, row.number),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.width(44.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    when (row.status) {
                                        AnswerReview.Status.ANSWERED -> stringResource(R.string.status_answered)
                                        AnswerReview.Status.UNANSWERED -> stringResource(R.string.status_unanswered)
                                        AnswerReview.Status.UNGRADED -> stringResource(R.string.status_ungraded)
                                    } + if (row.flagged) " · " + stringResource(R.string.status_flagged) else "",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissAnswerReview()
                    showSubmitDialog = true
                }) { Text(stringResource(R.string.back_to_submit)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissAnswerReview() }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

private fun formatMarks(marks: Double): String =
    if (marks == kotlin.math.floor(marks) && marks.isFinite()) {
        marks.toLong().toString()
    } else {
        marks.toString()
    }

/**
 * The "Question 2 of 10 · 1 mark · 0:07 here" control.
 *
 * Collects the dwell flow itself so the once-a-second tick is confined to
 * this composable. `dwellSeconds` also arrives as a parameter for
 * previews and tests that have no ViewModel.
 */
@Composable
private fun QuestionHeader(
    index: Int,
    total: Int,
    marks: Double,
    liveDwell: StateFlow<Long>? = null,
    dwellSeconds: Long = 0L,
    dwellActive: Boolean = true,
    onToggleDwellPause: () -> Unit = {}
) {
    val live by (liveDwell ?: MutableStateFlow(dwellSeconds))
        .collectAsStateWithLifecycle()
    // `semantics {}` is not composable: accessibility strings come from
    // Resources; the visible clock text renders via resources too, because
    // its three pieces are glued by questionProgressLabel, a pure function.
    val resources = LocalContext.current.resources
    val progressLabel = questionProgressLabel(
        question = resources.getString(R.string.question_progress_text, index + 1, total),
        marks = if (marks == 1.0) resources.getString(R.string.marks_one, formatMarks(marks))
        else resources.getString(R.string.marks_many, formatMarks(marks)),
        here = resources.getString(R.string.dwell_here, Dwell.format(live))
    )
    TextButton(
        onClick = onToggleDwellPause,
        modifier = Modifier
            .height(48.dp)
            .testTag("test-question-clock")
            .semantics {
                contentDescription = resources.getString(R.string.question_clock)
                stateDescription = if (dwellActive) resources.getString(R.string.clock_running)
                else resources.getString(R.string.clock_paused)
            }
    ) {
        Text(
            progressLabel + if (dwellActive) ""
            else " · ${stringResource(R.string.clock_paused)}",
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/**
 * "Question 3 of 10 · 1 mark · 0:42 here", from the localized pieces the
 * composable pulls out of resources. Built in one expression on purpose:
 * an inline `if` inside a `+` chain silently swallows the rest of the
 * string, which is how the dwell time used to disappear for every
 * single-mark question.
 */
internal fun questionProgressLabel(
    question: String,
    marks: String,
    here: String
): String = question + " · " + marks + " · " + here

@Composable
private fun OptionRow(
    text: String,
    elements: List<com.mcqapp.domain.ContentElement>,
    image: String?,
    selected: Boolean,
    revealed: Boolean,
    isCorrectOption: Boolean,
    multi: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val verdicts = verdictColors()
    val containerColor = when {
        revealed && isCorrectOption -> verdicts.correctContainer
        revealed && selected && !isCorrectOption -> verdicts.wrongContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        revealed && isCorrectOption -> verdicts.correctBorder
        revealed && selected && !isCorrectOption -> verdicts.wrongBorder
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    // The option body has to stay legible on the verdict container, which in
    // dark mode is no longer a pale green.
    val bodyColor = when {
        revealed && isCorrectOption -> verdicts.correctOnContainer
        revealed && selected && !isCorrectOption -> verdicts.wrongOnContainer
        else -> LocalContentColor.current
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            width = if (selected || revealed) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (multi) {
                Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = enabled)
            } else {
                RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            }
            CompositionLocalProvider(LocalContentColor provides bodyColor) {
                Column(modifier = Modifier.weight(1f)) {
                    ContentElements(elements.ifEmpty { listOf(com.mcqapp.domain.ContentElement.TextElement(text)) })
                    if (image != null) {
                        QuestionImage(
                            src = image,
                            contentDescription = stringResource(R.string.image_for_this_option),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            if (revealed) {
                // One decision drives both the glyph and its colour: an
                // option that is neither correct nor picked gets no marker.
                when (com.mcqapp.domain.Feedback.revealMarker(isCorrectOption, selected)) {
                    com.mcqapp.domain.Feedback.RevealMarker.CORRECT -> Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.correct_answer),
                        tint = verdicts.correctBorder
                    )
                    com.mcqapp.domain.Feedback.RevealMarker.WRONG -> Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.wrong_answer),
                        tint = verdicts.wrongBorder
                    )
                    com.mcqapp.domain.Feedback.RevealMarker.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun ExplanationCard(question: Question) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.explanation),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            // `resources` rather than stringResource: the fallback travels
            // inside a ContentElement, not a Text sink, so the render guard
            // cannot see it — a wide context read is the honest form.
            val explanationFallback = LocalContext.current.resources
                .getString(R.string.no_explanation)
            ContentElements(
                question.explanationElements.ifEmpty {
                    listOf(com.mcqapp.domain.ContentElement.TextElement(explanationFallback))
                },
                textStyle = MaterialTheme.typography.bodyMedium
            )
            QuestionImage(
                src = question.explanationImage,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun QuestionPalette(
    state: TestUiState,
    onPick: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.questions), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(44.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Count + key avoids boxing an Int on every composition.
                items(count = state.questionCount, key = { state.order.idAt(it) }) { index ->
                    val id = state.order.idAt(index)
                    val answered = state.selections[id]?.isNotEmpty() == true
                    val flagged = id in state.flagged
                    val current = index == state.currentIndex
                    val palette = verdictColors()
                    val bg = when {
                        current -> MaterialTheme.colorScheme.primary
                        answered -> palette.correctContainer
                        flagged -> palette.trickyContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val fg = when {
                        current -> MaterialTheme.colorScheme.onPrimary
                        answered -> palette.correctOnContainer
                        flagged -> palette.trickyOnContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val resources = LocalContext.current.resources
                    val status = when {
                        current -> resources.getString(R.string.palette_status_current)
                        answered -> resources.getString(R.string.status_answered)
                        flagged -> resources.getString(R.string.status_flagged)
                        else -> resources.getString(R.string.status_unanswered)
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(bg, RoundedCornerShape(8.dp))
                            .semantics {
                                contentDescription = resources.getString(
                                    R.string.palette_go_to_question, index + 1, status
                                )
                            }
                            .clickable { onPick(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", color = fg, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * The shared reading context above a passage member's stem. Carded and
 * distinctly styled so the learner can tell "what I read" from "what is
 * being asked" at a glance.
 */
@Composable
private fun PassageCard(passage: com.mcqapp.domain.Passage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                passage.title.ifBlank { stringResource(R.string.passage) },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            ContentElements(
                passage.elements,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            QuestionImage(
                src = passage.image,
                contentDescription = stringResource(R.string.passage_image),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
