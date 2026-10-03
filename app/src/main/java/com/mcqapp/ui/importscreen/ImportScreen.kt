package com.mcqapp.ui.importscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.mcqapp.ui.theme.verdictColors
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.mcqapp.domain.ImportWarnings
import com.mcqapp.util.Logger
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    importText: String?,
    navController: NavController,
    viewModel: ImportViewModel = viewModel(key = "import-direct")
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Memoized: these re-ran over all rows on every recomposition (frames
    // dropped while scrolling big previews).
    val newQuestions = remember(state.questions, state.duplicateIds, state.changedIds) {
        state.questions.filter { it.id !in state.duplicateIds && it.id !in state.changedIds }
    }
    val changedQuestions = remember(state.questions, state.changedIds) {
        state.questions.filter { it.id in state.changedIds }
    }
    val dupQuestions = remember(state.questions, state.duplicateIds) {
        state.questions.filter { it.id in state.duplicateIds }
    }
    // Advisory validation recomputes live as preview rows are edited.
    val warnings = remember(state.questions) {
        ImportWarnings.forFile(state.questions)
    }
    // Parse-time diagnostics: rows the reader skipped, not fatal.
    val parseWarnings = remember(state.parseWarnings) { state.parseWarnings }
    val report = state.importReport
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.cannot_open_file)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissError()
                    navController.popBackStack()
                }) { Text(stringResource(R.string.go_back)) }
            }
        )
    }
    if (state.importDone && report != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.import_complete)) },
            text = {
                Text(
                    "• ${report.newQuestions} new questions added\n" +
                        "• ${report.updatedQuestions} updated\n" +
                        "• ${report.duplicateQuestions} already existed (skipped)" +
                        (if (report.answersRefreshed > 0) {
                            " • ${report.answersRefreshed} answers refreshed"
                        } else {
                            ""
                        }) +
                        if (report.restoredBookmarks > 0 || report.restoredAttempts > 0) {
                            "\n• ${report.restoredBookmarks} bookmarks restored\n" +
                                "• ${report.restoredAttempts} attempts restored"
                        } else {
                            ""
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeImportResult()
                    navController.navigate("library") {
                        popUpTo("library") { inclusive = true }
                    }
                }) { Text(stringResource(R.string.ok)) }
            }
        )
    }
    fun editQuestion(question: com.mcqapp.data.io.QuestionDto) {
        Logger.d("IMPORTSCREEN", "edit clicked: id=${question.id}, " +
            "textLength=${question.text.length}, options=${question.options.size}")
        val order = viewModel.state.value.questions
        com.mcqapp.ui.editor.EditorSession.start(
            ids = order.map { it.id },
            index = order.indexOfFirst { it.id == question.id },
            reader = { id -> viewModel.state.value.questions.find { it.id == id } },
            writer = { dto -> viewModel.updateQuestionFromImport(dto) }
        )
        viewModel.markEditing(question.id)
        com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = question
        com.mcqapp.ui.importscreen.ImportDataHolder.editingFromImport = true
        Logger.d("IMPORTSCREEN", "holder armed: id=${question.id}, " +
            "editingFromImport=true; navigating to editor")
        navController.navigate(
            com.mcqapp.ui.navigation.editorRoute(question.id, "", "")
        )
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry) {
        val route = currentEntry?.destination?.route
        val pendingId = viewModel.lastEditedQuestionId
        Logger.d("IMPORTSCREEN", "backStack changed: route=$route, lastEditedInVm=$pendingId")
        if (route == "import/direct" && pendingId != null) {
            val edited = com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion
            Logger.d("IMPORTSCREEN", "returned from editor: holderPresent=${edited != null}, " +
                "holderId=${edited?.id}, holderTextLength=${edited?.text?.length}")
            if (edited != null && edited.id == pendingId) {
                viewModel.updateQuestionFromImport(edited)
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = null
            } else {
                Logger.d("IMPORTSCREEN", "returned from editor: nothing to apply " +
                    "(holder missing or id mismatch), clearing flag")
            }
            viewModel.clearLastEdited()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val pendingDocx = com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxBytes
        if (pendingDocx != null) {
            com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxBytes = null
            viewModel.loadDocx(pendingDocx)
        } else if (importText != null) {
            viewModel.loadJsonText(importText)
            // Drop the global copy once consumed: the VM owns the text now
            // (fingerprint-gated), and a 25k-question file is ~9MB retained.
            // Identity-guarded so a newer pick made meanwhile is never lost.
            if (com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonText === importText) {
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonText = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_questions)) },
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.loading))
            }
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(stringResource(R.string.paper_details), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.paperTitle,
                        onValueChange = viewModel::updatePaperTitle,
                        label = { Text(stringResource(R.string.paper_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.paperDescription,
                        onValueChange = viewModel::updatePaperDescription,
                        label = { Text(stringResource(R.string.description_optional)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (state.durationMinutes == 0) "" else state.durationMinutes.toString(),
                            onValueChange = { input -> viewModel.updateDuration(input.filter { it.isDigit() }.toIntOrNull() ?: 0) },
                            label = { Text(stringResource(R.string.duration_min)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = if (state.negativeMarking == 0.0) "" else state.negativeMarking.toString(),
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() || it == '.' }
                                viewModel.updateNegativeMarking(filtered.toDoubleOrNull() ?: 0.0)
                            },
                            label = { Text(stringResource(R.string.negative_marking)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.categoryName,
                        onValueChange = viewModel::updateCategoryName,
                        label = { Text(stringResource(R.string.category_name_for_all_questions)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "New questions (${newQuestions.size}) — will be added",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (parseWarnings.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Skipped rows (${parseWarnings.size}) — imported without these",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    items(parseWarnings.take(20), key = { "parse|$it" }) { warning ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                warning,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    if (parseWarnings.size > 20) {
                        item {
                            Text(
                                "+${parseWarnings.size - 20} more skipped rows",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                if (warnings.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Warnings (${warnings.size}) — imports fine, but check these",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    items(warnings.take(20), key = { it.questionId + "|" + it.message }) { warning ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    warning.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    warning.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    if (warnings.size > 20) {
                        item {
                            Text(
                                "+${warnings.size - 20} more warnings",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                items(newQuestions, key = { it.id }) { question ->
                    PreviewQuestionCard(
                        question = question,
                        dimmed = false,
                        badge = null,
                        onDelete = { viewModel.deleteQuestion(question.id) },
                        onEdit = { editQuestion(question) }
                    )
                }

                if (changedQuestions.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Changed (${changedQuestions.size}) — will update the existing question",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    items(changedQuestions, key = { it.id }) { question ->
                        PreviewQuestionCard(
                            question = question,
                            dimmed = false,
                            badge = "CHANGED",
                            onDelete = { viewModel.deleteQuestion(question.id) },
                            onEdit = { editQuestion(question) }
                        )
                    }
                }

                if (dupQuestions.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Duplicates (${dupQuestions.size}) — already in library, will be skipped",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.matched_by_question_text_options),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    items(dupQuestions, key = { it.id }) { question ->
                        PreviewQuestionCard(
                            question = question,
                            dimmed = true,
                            badge = "DUPLICATE",
                            onDelete = { viewModel.deleteQuestion(question.id) },
                            onEdit = { editQuestion(question) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.import() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.paperTitle.isNotBlank() &&
                    (newQuestions.isNotEmpty() || changedQuestions.isNotEmpty()) &&
                    !state.importing
            ) {
                if (state.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (newQuestions.isEmpty() && changedQuestions.isEmpty()) {
                        "Nothing new to import"
                    } else if (changedQuestions.isEmpty()) {
                        "Import ${newQuestions.size} new questions"
                    } else if (newQuestions.isEmpty()) {
                        "Update ${changedQuestions.size} questions"
                    } else {
                        "Import ${newQuestions.size} new • update ${changedQuestions.size}"
                    }
                )
            }
        }
    }
}

@Composable
private fun PreviewQuestionCard(
    question: com.mcqapp.data.io.QuestionDto,
    dimmed: Boolean,
    badge: String?,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.55f else 1f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    question.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_question))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_question))
                }
            }
            QuestionImage(
                src = question.image,
                contentDescription = stringResource(R.string.question_image),
                modifier = Modifier.padding(top = 4.dp)
            )
            question.options.forEach { option ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    val isCorrect = option.id in question.correctOptionIds
                    Text(
                        if (isCorrect) "✓" else "○",
                        color = if (isCorrect) verdictColors().correctBorder else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(18.dp),
                        fontWeight = if (isCorrect) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(option.text, style = MaterialTheme.typography.bodySmall)
                }
                if (option.image != null) {
                    QuestionImage(
                        src = option.image,
                        modifier = Modifier.padding(start = 18.dp, top = 1.dp)
                    )
                }
            }
            if (question.explanation.isNotBlank()) {
                Text(
                    "Explanation: ${question.explanation}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            QuestionImage(
                src = question.explanationImage,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
