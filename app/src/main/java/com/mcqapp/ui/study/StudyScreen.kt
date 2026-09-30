package com.mcqapp.ui.study

import android.app.Application
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.StudyReason
import com.mcqapp.ui.StudyViewModelFactory
import com.mcqapp.util.QuestionImage

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
    navController: NavController
) {
    val context = LocalContext.current
    val viewModel: StudyViewModel = viewModel(
        key = "study-$paperId",
        factory = StudyViewModelFactory(context.applicationContext as Application, paperId)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Study") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
    Column(
        modifier = Modifier
            .fillMaxSize()
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
        if (!state.revealed) {
            Button(
                onClick = { viewModel.reveal() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show answer")
            }
        } else {
            GradeButtons(enabled = !state.grading, onGrade = { viewModel.grade(it) })
        }
        Spacer(Modifier.height(24.dp))
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
                    "New question",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(question.text, style = MaterialTheme.typography.bodyMedium)
            QuestionImage(src = question.image, modifier = Modifier.padding(top = 4.dp))
            question.options.forEach { option ->
                val isCorrect = option.id in question.correctOptionIds
                val isSelected = option.id in selection
                val container = when {
                    revealed && isCorrect -> MaterialTheme.colorScheme.primaryContainer
                    revealed && isSelected -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                val mark = when {
                    revealed && isCorrect -> "✓"
                    revealed && isSelected -> "✗"
                    isSelected -> "•"
                    else -> "○"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(container, RoundedCornerShape(8.dp))
                        .clickable(enabled = !revealed) { onToggleOption(option.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        mark,
                        modifier = Modifier.width(20.dp),
                        color = if (revealed && isCorrect) MaterialTheme.colorScheme.primary
                        else if (revealed && isSelected) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(option.text, style = MaterialTheme.typography.bodySmall)
                        QuestionImage(src = option.image)
                    }
                }
            }
            if (revealed && question.explanation.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Explanation", style = MaterialTheme.typography.labelMedium)
                Text(question.explanation, style = MaterialTheme.typography.bodySmall)
                QuestionImage(src = question.explanationImage)
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
                "You keep missing this one",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun GradeButtons(enabled: Boolean, onGrade: (ReviewGrade) -> Unit) {
    Column {
        Text(
            "How well did you recall it?",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradeButton("Again", MaterialTheme.colorScheme.error, enabled, Modifier.weight(1f)) {
                onGrade(ReviewGrade.AGAIN)
            }
            GradeButton("Hard", MaterialTheme.colorScheme.tertiary, enabled, Modifier.weight(1f)) {
                onGrade(ReviewGrade.HARD)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradeButton("Good", MaterialTheme.colorScheme.primary, enabled, Modifier.weight(1f)) {
                onGrade(ReviewGrade.GOOD)
            }
            GradeButton("Easy", MaterialTheme.colorScheme.secondary, enabled, Modifier.weight(1f)) {
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
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
                Text("Back to library")
            }
            return@Column
        } else {
            Text(
                "Session complete",
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
            Text("Study again")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
