package com.mcqapp.ui.study

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Dwell
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.StudyReason
import com.mcqapp.ui.StudyViewModelFactory
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

/**
 * Study mode: one question at a time, show the answer, then grade it. The
 * four grades are the learner's own recall judgement, not a correctness
 * score — marks, negative marking and timers are deliberately absent here so
 * the loop stays honest and takes about ten seconds a card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    repository: McqRepository,
    paperId: String,
    navController: NavController,
    categoryIds: List<String> = emptyList(),
    leechesOnly: Boolean = false
) {
    val context = LocalContext.current
    val viewModel: StudyViewModel = viewModel(
        key = "study-$paperId-${categoryIds.joinToString(",")}-$leechesOnly",
        factory = StudyViewModelFactory(
            context.applicationContext as Application,
            paperId,
            categoryIds,
            leechesOnly
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

    val showStudyHeader =
        !state.loading && state.loadError == null && !state.finished

    Scaffold(
        topBar = {
            if (showStudyHeader) {
                StudyStickyHeader(
                    state = state,
                    liveDwell = liveDwell,
                    onBack = { navController.popBackStack() },
                    onStop = { viewModel.stop() },
                    onDismissBreak = { viewModel.dismissBreak() },
                    onToggleClock = { viewModel.toggleQuestionClock() }
                )
            } else {
                BackOnlyBar(onBack = { navController.popBackStack() })
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadError != null -> StudyLoadError(
                    message = state.loadError ?: "",
                    onRetry = { viewModel.retry() },
                    onDone = { navController.popBackStack() }
                )
                state.finished -> StudySummary(
                    state = state,
                    onRestart = { viewModel.restart() },
                    onEnableNewCards = { viewModel.enableNewCards() },
                    onDone = { navController.popBackStack() }
                )
                else -> StudyBody(
                    state = state,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun StudyBody(
    state: StudyUiState,
    viewModel: StudyViewModel
) {
    val question = state.question ?: return
    // The dwell clock only charges while the question card is actually in the
    // viewport: scrolling a long passage — or, after reveal, the answer — past
    // the card pauses it, the same rule the test screen applies to dialogs.
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var questionOnScreen by remember { mutableStateOf(true) }
    fun reportVisibility() {
        val card = cardCoords
        val container = containerCoords
        if (card == null || container == null || !card.isAttached || !container.isAttached) return
        val top = card.localPositionOf(container, Offset.Zero).y
        questionOnScreen = top < container.size.height && top + card.size.height > 0f
    }
    val cardPositioned = Modifier.onGloballyPositioned { cardCoords = it; reportVisibility() }
    LaunchedEffect(questionOnScreen) {
        viewModel.setQuestionVisible(questionOnScreen)
    }
    if (state.simplified) {
        // Like test mode: the question scrolls, but Check, Guess and Next live
        // in a fixed footer so they are always tappable without scrolling.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .onGloballyPositioned { containerCoords = it; reportVisibility() }
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                QuestionCard(
                    question = question,
                    selection = state.currentSelection,
                    revealed = state.revealed,
                    reason = state.current?.reason,
                    onToggleOption = { viewModel.toggleOption(it) },
                    passage = state.passage,
                    modifier = cardPositioned
                )
                Spacer(Modifier.height(12.dp))
                if (state.revealed) {
                    SimplifiedResultBlock(state = state)
                }
            }
            SimplifiedFooter(state = state, viewModel = viewModel)
        }
    } else {
        // Same fixed-footer shape as Simplified: the answer and the four
        // buttons never scroll away either.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .onGloballyPositioned { containerCoords = it; reportVisibility() }
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                QuestionCard(
                    question = question,
                    selection = state.currentSelection,
                    revealed = state.revealed,
                    reason = state.current?.reason,
                    onToggleOption = { viewModel.toggleOption(it) },
                    passage = state.passage,
                    modifier = cardPositioned
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                GradeError(state.gradeError)
                if (state.gradedAndStuck) {
                    // The grade is already stored; re-grading would double it.
                    // Only the next card is missing (consolidated_bugs.md I3).
                    Button(
                        onClick = { viewModel.loadNext() },
                        enabled = !state.grading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                } else if (!state.revealed) {
                    Button(
                        onClick = { viewModel.reveal() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.show_answer))
                    }
                } else {
                    GradeButtons(
                        enabled = !state.grading,
                        previews = state.previews,
                        onGrade = { viewModel.grade(it) }
                    )
                }
            }
        }
    }
}

/**
 * The result of a Simplified check. Lives in the scrollable content: it is
 * read, not tapped to proceed. The change-grade control itself sticks to the
 * footer beside Next, so correcting a misclick never requires scrolling.
 */
@Composable
private fun SimplifiedResultBlock(state: StudyUiState) {
    val result = state.lastResult ?: return
    SimplifiedResultCard(result = result)
}

/**
 * The always-visible Simplified actions, mirroring test mode's fixed footer:
 * Guess + Check before reveal, Next after. None of these ever scrolls away.
 */
@Composable
private fun SimplifiedFooter(state: StudyUiState, viewModel: StudyViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        GradeError(state.gradeError)
        if (!state.revealed) {
            // Pre-commit: once the answer is visible a guess declaration would be
            // retroactive, so the toggle lives only on the unanswered card.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.guessing))
                    Text(
                        stringResource(R.string.guessing_this_is_a_guess),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = state.isGuess,
                    onCheckedChange = { viewModel.setGuess(it) }
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.check() },
                modifier = Modifier.fillMaxWidth().testTag("study-check")
            ) {
                Text(stringResource(R.string.check))
            }
        } else {
            val question = state.question
            var showPicker by remember(question?.id) { mutableStateOf(false) }
            if (showPicker) {
                GradeButtons(
                    enabled = !state.grading,
                    previews = state.previews,
                    onGrade = {
                        viewModel.changeGrade(it)
                        showPicker = false
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showPicker = !showPicker }) {
                    Text(stringResource(R.string.change_grade))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.next() },
                    modifier = Modifier.weight(1f).testTag("study-next"),
                    // The card is graded and stuck: re-tapping Next would
                    // double-persist it (consolidated_bugs.md I3).
                    enabled = !state.grading && !state.gradedAndStuck
                ) {
                    Text(stringResource(R.string.next))
                }
            }
        }
    }
}

/** A failed grade write stays on the card with a retryable button. */
@Composable
private fun GradeError(message: String?) {
    if (message == null) return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SimplifiedResultCard(result: com.mcqapp.ui.study.StudyResult) {
    Card(modifier = Modifier.fillMaxWidth().testTag("study-result")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                (if (result.correct) "✓ " else "✗ ") +
                    stringResource(
                        if (result.correct) R.string.correct else R.string.incorrect
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = if (result.correct) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
            // One Text per branch: the string-usage test requires each
            // stringResource to render directly inside a Text.
            if (!result.correct) {
                Text(
                    stringResource(R.string.incorrect_answer_review_soon),
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (result.wasGuess) {
                Text(
                    stringResource(R.string.guessed_right_marked_again),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    stringResource(R.string.answered_in_xs, result.dwellSeconds) + " — " +
                        result.grade.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Next in ${result.nextIn}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackOnlyBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        }
    )
}

@Composable
private fun StudyStickyHeader(
    state: StudyUiState,
    liveDwell: Long,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onDismissBreak: () -> Unit,
    onToggleClock: () -> Unit
) {
    // `semantics {}` is not composable, so the accessibility strings come
    // from Resources (the OverviewSection tooltip pattern); the clock text
    // itself renders via stringResource inside its Text.
    val resources = LocalContext.current.resources

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        // Custom top-bar content: TopAppBar's built-in status-bar inset does
        // not apply, so pad explicitly or the title collides with the clock.
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.paperTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("study-paper-title")
                    )
                    // Copy lives here, not in the ViewModel: a unit test runs
                    // without app resources (C19).
                    val scopeText = when (val label = state.scopeLabel) {
                        ScopeLabel.WholePaper -> ""
                        is ScopeLabel.One -> label.title
                        is ScopeLabel.Many ->
                            stringResource(R.string.study_scope_many, label.count)
                    }
                    if (scopeText.isNotEmpty()) {
                        Text(
                            scopeText,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("study-scope-label")
                        )
                    }
                }
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.testTag("study-done")
                ) {
                    Text(stringResource(R.string.done_for_now))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.reviewed_this_session, state.reviewed),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onToggleClock,
                    enabled = !state.revealed,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("study-question-clock")
                        .semantics {
                            contentDescription = resources.getString(R.string.question_clock)
                            stateDescription =
                                if (state.clockRunning) resources.getString(R.string.clock_running)
                                else resources.getString(R.string.clock_paused)
                        }
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Dwell.format(liveDwell) +
                            if (state.clockRunning) ""
                            else " · ${stringResource(R.string.clock_paused)}"
                    )
                }
            }
            if (state.showBreakStrip) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.break_suggestion, state.reviewed),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissBreak) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    selection: Set<String>,
    revealed: Boolean,
    reason: StudyReason?,
    onToggleOption: (String) -> Unit,
    passage: com.mcqapp.domain.Passage? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Shared context: the passage renders above every member whenever
            // it comes due — no queue grouping, the schedule stays per question.
            if (passage != null) {
                Text(
                    passage.title.ifBlank { stringResource(R.string.passage) },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                ContentElements(
                    passage.elements,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                QuestionImage(
                    src = passage.image,
                    contentDescription = stringResource(R.string.passage_image),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            // Why this card was pulled. Optional context, never a demand.
            when (reason) {
                StudyReason.LEECH -> {
                    LeeChip()
                    Spacer(Modifier.height(6.dp))
                }
                StudyReason.NEW -> {
                    Text(
                        stringResource(R.string.new_question),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(6.dp))
                }
                StudyReason.LOW_RECALL_RISK -> {
                    Text(
                        stringResource(R.string.likely_to_be_forgotten_soon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                }
                StudyReason.LEARNING -> {
                    Text(
                        stringResource(R.string.learning_step),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                }
                StudyReason.DUE, null -> {}
            }
            // Bumped a step: the stem is the primary reading text and was set
            // small relative to the option markers around it.
            ContentElements(question.elements, textStyle = MaterialTheme.typography.bodyLarge)
            QuestionImage(
                src = question.image,
                contentDescription = stringResource(R.string.question_image),
                modifier = Modifier.padding(top = 4.dp)
            )
            question.options.forEach { option ->
                val isCorrect = option.id in question.correctOptionIds
                val isSelected = option.id in selection
                val container = when {
                    revealed && isCorrect -> MaterialTheme.colorScheme.primaryContainer
                    revealed && isSelected -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                // A multi-correct question can take several picks, so its
                // marker is a square — the same circle/square distinction the
                // test screen draws with a radio button vs a checkbox.
                val multi = question.isMultiCorrect
                val mark = when {
                    revealed && isCorrect -> "✓"
                    revealed && isSelected -> "✗"
                    isSelected && multi -> "▣"
                    // Filled circle, not a bullet: "•" renders visibly smaller
                    // than "○" at the same size, so selecting shrank the mark.
                    isSelected -> "●"
                    multi -> "▢"
                    else -> "○"
                }
                // Announced as a checkbox with a state description: the glyph
                // alone left TalkBack reading only "✓ button".
                val optionState = when {
                    revealed && isCorrect -> "Correct answer"
                    revealed && isSelected -> "Selected, wrong answer"
                    isSelected -> "Selected"
                    else -> "Not selected"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(container, RoundedCornerShape(8.dp))
                        .selectable(
                            selected = isSelected,
                            enabled = !revealed,
                            role = Role.Checkbox,
                        ) { onToggleOption(option.id) }
                        .padding(8.dp)
                        .semantics { stateDescription = optionState },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        mark,
                        modifier = Modifier.width(32.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (revealed && isCorrect) MaterialTheme.colorScheme.primary
                        else if (revealed && isSelected) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        ContentElements(option.elements, textStyle = MaterialTheme.typography.bodyLarge)
                        QuestionImage(src = option.image, contentDescription = stringResource(R.string.image_for_this_option))
                    }
                }
            }
            if (revealed && question.explanation.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.explanation), style = MaterialTheme.typography.labelMedium)
                ContentElements(question.explanationElements, textStyle = MaterialTheme.typography.bodyLarge)
                QuestionImage(src = question.explanationImage, contentDescription = stringResource(R.string.explanation_image))
            }
        }
    }
}

@Composable
private fun LeeChip() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.height(14.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.you_keep_missing_this_one),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun GradeButtons(
    enabled: Boolean,
    previews: Map<ReviewGrade, String>,
    onGrade: (ReviewGrade) -> Unit
) {
    Column {
        Text(
            stringResource(R.string.how_well_did_you_recall_it),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradeButton("Again", MaterialTheme.colorScheme.error, enabled, previews[ReviewGrade.AGAIN], Modifier.weight(1f)) {
                onGrade(ReviewGrade.AGAIN)
            }
            GradeButton("Hard", MaterialTheme.colorScheme.tertiary, enabled, previews[ReviewGrade.HARD], Modifier.weight(1f)) {
                onGrade(ReviewGrade.HARD)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradeButton("Good", MaterialTheme.colorScheme.primary, enabled, previews[ReviewGrade.GOOD], Modifier.weight(1f)) {
                onGrade(ReviewGrade.GOOD)
            }
            GradeButton("Easy", MaterialTheme.colorScheme.secondary, enabled, previews[ReviewGrade.EASY], Modifier.weight(1f)) {
                onGrade(ReviewGrade.EASY)
            }
        }
    }
}

@Composable
private fun GradeButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    preview: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        // The delay each grade would produce, as in Anki. Without it the learner
        // cannot tell "Again" from "Hard" before committing, and after Track C
        // the four intervals are no longer an obvious ordering of the labels.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (preview != null) {
                Text(
                    preview,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    // Tagged rather than matched on text: the value depends on
                    // the clock and the day boundary, so a text assertion would
                    // only pass at some times of day.
                    modifier = Modifier.testTag("grade-preview-$label")
                )
            }
        }
    }
}

@Composable
private fun StudyLoadError(message: String, onRetry: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.back_to_library)) }
    }
}

@Composable
private fun StudySummary(
    state: StudyUiState,
    onRestart: () -> Unit,
    onEnableNewCards: () -> Unit,
    onDone: () -> Unit
) {
    // The ViewModel says why it is empty; the copy is resolved here (C19).
    val emptyText = when (state.emptyReason) {
        EmptyReason.None -> ""
        EmptyReason.NothingDue -> stringResource(R.string.nothing_due)
        EmptyReason.NothingDueInScope -> stringResource(R.string.nothing_due_in_scope)
        EmptyReason.NothingTricky -> stringResource(R.string.nothing_tricky)
        EmptyReason.NothingTrickyInScope -> stringResource(R.string.nothing_tricky_in_scope)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (emptyText.isNotEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            // New cards exist but are switched off: offer the toggle here,
            // where the learner is looking at an empty session, without
            // sending them hunting through settings.
            if (!state.introduceNewCards && state.freshAvailable) {
                Text(
                    stringResource(R.string.new_cards_are_switched_off),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(
                    onClick = onEnableNewCards,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.introduce_new_cards))
                }
                Spacer(Modifier.height(8.dp))
            }
            // There is nothing to repeat, so only offer a way out.
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back_to_library))
            }
            return@Column
        } else {
            Text(
                stringResource(R.string.session_complete),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${state.reviewed} reviewed • ${state.againCount} again • ${state.goodCount} remembered",
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.reviewed > 0) {
                Spacer(Modifier.height(4.dp))
                // Stats only, no goals: how the sitting went, not a score to
                // defend.
                Text(
                    stringResource(
                        R.string.session_accuracy,
                        state.goodCount * 100 / state.reviewed
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.study_again))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.done))
        }
    }
}
