package com.mcqapp.ui.study

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
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
    leechesOnly: Boolean = false
) {
    val context = LocalContext.current
    val viewModel: StudyViewModel = viewModel(
        key = "study-$paperId-$leechesOnly",
        factory = StudyViewModelFactory(
            context.applicationContext as Application,
            paperId,
            leechesOnly
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.study)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
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
                    onDone = { navController.popBackStack() }
                )
                else -> StudyBody(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun StudyBody(state: StudyUiState, viewModel: StudyViewModel) {
    val question = state.currentQuestion ?: return
    if (state.simplified) {
        // Like test mode: the question scrolls, but Check, Guess and Next live
        // in a fixed footer so they are always tappable without scrolling.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                StudyHeader(state)
                Spacer(Modifier.height(12.dp))
                QuestionCard(
                    question = question,
                    selection = state.currentSelection,
                    revealed = state.revealed,
                    reason = state.reasons[question.id],
                    onToggleOption = { viewModel.toggleOption(it) }
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
                    .padding(16.dp)
            ) {
                StudyHeader(state)
                Spacer(Modifier.height(12.dp))
                QuestionCard(
                    question = question,
                    selection = state.currentSelection,
                    revealed = state.revealed,
                    reason = state.reasons[question.id],
                    onToggleOption = { viewModel.toggleOption(it) }
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (!state.revealed) {
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
            val question = state.currentQuestion
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
                    enabled = !state.grading
                ) {
                    Text(stringResource(R.string.next))
                }
            }
        }
    }
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
                    stringResource(R.string.guessed_right_marked_hard),
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

@Composable
private fun StudyHeader(state: StudyUiState) {
    Column {
        Text(
            state.paperTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "${state.remaining} left in this session",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    selection: Set<String>,
    revealed: Boolean,
    reason: StudyReason?,
    onToggleOption: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (reason == StudyReason.LEECH) {
                LeeChip()
                Spacer(Modifier.height(6.dp))
            } else if (reason == StudyReason.NEW) {
                Text(
                    stringResource(R.string.new_question),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.height(6.dp))
            }
            ContentElements(question.elements, textStyle = MaterialTheme.typography.titleMedium)
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
                    isSelected -> "•"
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
                        modifier = Modifier.width(28.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (revealed && isCorrect) MaterialTheme.colorScheme.primary
                        else if (revealed && isSelected) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        ContentElements(option.elements, textStyle = MaterialTheme.typography.bodyMedium)
                        QuestionImage(src = option.image, contentDescription = stringResource(R.string.image_for_this_option))
                    }
                }
            }
            if (revealed && question.explanation.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.explanation), style = MaterialTheme.typography.labelMedium)
                ContentElements(question.explanationElements, textStyle = MaterialTheme.typography.bodyMedium)
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
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.emptyReason.isNotBlank()) {
            Text(
                state.emptyReason,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
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
