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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.app.Application
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Dwell
import com.mcqapp.domain.Feedback
import com.mcqapp.domain.Question
import com.mcqapp.domain.SubmitSummary
import com.mcqapp.ui.TestViewModelFactory
import com.mcqapp.util.Logger
import com.mcqapp.util.QuestionImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestSessionScreen(
    repository: McqRepository,
    paperId: String,
    categoryIds: List<String>,
    mistakesOnly: Boolean = false,
    navController: NavController
) {
    val context = LocalContext.current
    val viewModel: TestViewModel = viewModel(
        key = "test-$paperId-${categoryIds.joinToString(",")}-mistakes=$mistakesOnly",
        factory = TestViewModelFactory(
            context.applicationContext as Application,
            paperId,
            categoryIds,
            mistakesOnly
        )
    )
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.attemptId) {
        state.attemptId?.let { id ->
            navController.navigate("results/$id") {
                popUpTo("library") { inclusive = false }
            }
        }
    }

    var showPalette by remember { mutableStateOf(false) }
    var showSubmitDialog by remember { mutableStateOf(false) }
    var ungradedDismissed by remember { mutableStateOf(false) }
    val ungradedTotal = remember(state.questions) {
        state.questions.count { it.correctOptionIds.isEmpty() }
    }
    val resumeOffer = state.resumeOffer
    if (resumeOffer != null) {
        val answered = resumeOffer.selections.count { it.value.isNotEmpty() }
        val minutes = resumeOffer.remainingSeconds / 60
        val seconds = resumeOffer.remainingSeconds % 60
        AlertDialog(
            onDismissRequest = { /* explicit choice required */ },
            title = { Text("Resume previous attempt?") },
            text = {
                Text(
                    "$answered question(s) answered" +
                        if (resumeOffer.totalSeconds > 0) {
                            ", %02d:%02d left.".format(minutes, seconds)
                        } else {
                            "."
                        } +
                        " Your progress was saved when the app closed."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resume() }) { Text("Resume") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.discardResume() }) { Text("Start fresh") }
            }
        )
    }
    if (!state.loading && ungradedTotal > 0 && !ungradedDismissed) {
        AlertDialog(
            onDismissRequest = { ungradedDismissed = true },
            title = { Text("Questions without an answer key") },
            text = {
                Text(
                    if (ungradedTotal == 1)
                        "1 question has no answer key and won't be scored. " +
                            "You can still answer it for practice."
                    else
                        "$ungradedTotal questions have no answer key and won't be scored. " +
                            "You can still answer them for practice."
                )
            },
            confirmButton = {
                TextButton(onClick = { ungradedDismissed = true }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        ungradedDismissed = true
                        navController.popBackStack()
                    }
                ) { Text("Go back") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.paper?.title ?: "Test",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.totalSeconds > 0) {
                        val minutes = state.remainingSeconds / 60
                        val seconds = state.remainingSeconds % 60
                        val urgent = state.remainingSeconds <= 60
                        IconButton(onClick = {}) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "%02d:%02d".format(minutes, seconds),
                                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    val question = state.currentQuestion
                    if (question != null) {
                        IconButton(onClick = { viewModel.toggleBookmarkCurrent() }) {
                            Icon(
                                if (question.id in state.bookmarked) Icons.Default.Bookmark
                                else Icons.Default.BookmarkBorder,
                                contentDescription = "Bookmark question",
                                tint = if (question.id in state.bookmarked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { viewModel.toggleFlag() }) {
                            Icon(
                                Icons.Default.Flag,
                                contentDescription = "Flag question",
                                tint = if (question.id in state.flagged) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface
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
                Text("Loading…")
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
                            Text("Dismiss")
                        }
                    }
                }
            }

            val question = state.currentQuestion
            if (question == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No questions in this selection")
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    "Question ${state.currentIndex + 1} of ${state.questions.size}" +
                        " · ${formatMarks(question.marks)} " +
                        if (question.marks == 1.0) "mark" else "marks" +
                        " · ${Dwell.format(state.dwellSeconds[question.id] ?: 0L)} here",
                    style = MaterialTheme.typography.labelMedium
                )
                if (state.practiceMode) {
                    Text(
                        "Practice — answers shown instantly",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (state.mistakesOnly) {
                    Text(
                        "Mistakes round — previously missed questions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (question.isMultiCorrect) {
                    Text(
                        "Multiple correct — select all that apply",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (question.correctOptionIds.isEmpty()) {
                    Text(
                        "No answer key — not scored",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(question.text, style = MaterialTheme.typography.titleMedium)
                QuestionImage(src = question.image, modifier = Modifier.padding(top = 8.dp))

                Spacer(Modifier.height(16.dp))
                val questionUngraded = question.correctOptionIds.isEmpty()
                val manuallyRevealed = question.id in state.revealed
                question.options.forEach { option ->
                    val selected = option.id in (state.selections[question.id] ?: emptySet())
                    val revealed = Feedback.liveReveal(
                        state.practiceMode, manuallyRevealed, questionUngraded
                    )
                    val isCorrectOption = option.id in question.correctOptionIds
                    OptionRow(
                        text = option.text,
                        image = option.image,
                        selected = selected,
                        revealed = revealed,
                        isCorrectOption = isCorrectOption,
                        multi = question.isMultiCorrect,
                        onClick = { viewModel.toggleOption(option.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (Feedback.showExplanation(state.practiceMode, manuallyRevealed)) {
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
                    enabled = state.currentIndex > 0
                ) {
                    Text("Previous")
                }
                TextButton(onClick = { showPalette = !showPalette }) {
                    Text("${state.answeredCount}/${state.questions.size} answered")
                }
                if (state.currentIndex < state.questions.size - 1) {
                    Button(onClick = { viewModel.next() }) { Text("Next") }
                } else {
                    Button(onClick = { showSubmitDialog = true }) { Text("Submit") }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!state.practiceMode) {
                    OutlinedButton(
                        onClick = { viewModel.revealCurrent() },
                        enabled = question.id !in state.revealed,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Show answer")
                    }
                }
                Button(
                    onClick = { showSubmitDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Submit test")
                }
            }
        }
    }

    if (showSubmitDialog) {
        val slowest = state.dwellSeconds.maxByOrNull { it.value }
        val slowestIndex = slowest?.let { entry ->
            state.questions.indexOfFirst { it.id == entry.key }.takeIf { it >= 0 }
        }
        val summary = SubmitSummary.Summary(
            answered = state.answeredCount,
            total = state.questions.size,
            flagged = state.flagged.size,
            ungraded = ungradedTotal,
            slowestQuestion = slowestIndex?.plus(1),
            slowestSeconds = slowest?.value ?: 0L
        )
        val firstFlagged = state.questions.indexOfFirst { it.id in state.flagged }
        AlertDialog(
            onDismissRequest = { showSubmitDialog = false },
            title = { Text("Submit test?") },
            text = {
                Text(SubmitSummary.lines(summary).joinToString("\n"))
            },
            confirmButton = {
                TextButton(onClick = {
                    showSubmitDialog = false
                    viewModel.submit()
                }) { Text("Submit") }
            },
            dismissButton = {
                if (firstFlagged >= 0) {
                    TextButton(onClick = {
                        showSubmitDialog = false
                        viewModel.goTo(firstFlagged)
                    }) { Text("Review flagged") }
                } else {
                    TextButton(onClick = { showSubmitDialog = false }) { Text("Cancel") }
                }
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

@Composable
private fun OptionRow(
    text: String,
    image: String?,
    selected: Boolean,
    revealed: Boolean,
    isCorrectOption: Boolean,
    multi: Boolean,
    onClick: () -> Unit
) {
    val containerColor = when {
        revealed && isCorrectOption -> Color(0xFFC8E6C9)
        revealed && selected && !isCorrectOption -> Color(0xFFFFCDD2)
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        revealed && isCorrectOption -> Color(0xFF2E7D32)
        revealed && selected && !isCorrectOption -> Color(0xFFC62828)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                RadioButton(selected = selected, onClick = onClick)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text)
                if (image != null) {
                    QuestionImage(src = image, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (revealed) {
                Icon(
                    if (isCorrectOption) Icons.Default.Check else if (selected) Icons.Default.Close else Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isCorrectOption) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
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
                "Explanation",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                question.explanation.ifBlank { "No explanation provided." },
                style = MaterialTheme.typography.bodyMedium
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
            Text("Questions", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(44.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.questions.indices.toList()) { index ->
                    val question = state.questions[index]
                    val answered = state.selections[question.id]?.isNotEmpty() == true
                    val flagged = question.id in state.flagged
                    val current = index == state.currentIndex
                    val bg = when {
                        current -> MaterialTheme.colorScheme.primary
                        answered -> Color(0xFFA5D6A7)
                        flagged -> Color(0xFFFFCC80)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val fg = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    val status = when {
                        current -> "current"
                        answered -> "answered"
                        flagged -> "flagged"
                        else -> "unanswered"
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(bg, RoundedCornerShape(8.dp))
                            .semantics {
                                contentDescription = "Go to question ${index + 1}, $status"
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
