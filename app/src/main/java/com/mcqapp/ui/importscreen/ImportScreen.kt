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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.mcqapp.util.ContentElements
import com.mcqapp.util.Logger
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    importBytes: ByteArray?,
    importUri: android.net.Uri?,
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
    // The button's gate is pure so it can be pinned by a JVM test: a backup is
    // imported whole even when its previewed paper shows nothing new.
    val importAction = importButton(state, newQuestions.size, changedQuestions.size)
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
            val pendingName = com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxName
            com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxName = null
            viewModel.loadDocx(pendingDocx, pendingName)
        } else if (importBytes != null) {
            viewModel.loadJsonBytes(importBytes)
            // Drop the global copy once consumed: the VM owns the parse now
            // (fingerprint-gated), and a 25k-question bank is ~9MB retained.
            // Identity-guarded so a newer pick made meanwhile is never lost.
            if (com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonBytes === importBytes) {
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonBytes = null
            }
        } else if (importUri != null) {
            viewModel.loadJsonUri(importUri)
            if (com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonUri == importUri) {
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonUri = null
            }
        } else {
            // Process death: the static holder is empty and no text was passed,
            // so nothing will ever flip loading off. Show an error instead of
            // an infinite spinner.
            viewModel.setNoSourceError()
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
                item(contentType = "form") {
                    Text(stringResource(R.string.paper_details), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SuggestionTextField(
                        label = stringResource(R.string.paper_title),
                        value = state.paperTitle,
                        onValueChange = viewModel::updatePaperTitle,
                        suggestions = state.paperTitleSuggestions
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
                    SuggestionTextField(
                        label = stringResource(R.string.category_name_for_all_questions),
                        value = state.categoryName,
                        onValueChange = viewModel::updateCategoryName,
                        suggestions = state.categorySuggestions
                    )
                }

                if (state.previewTruncated) {
                    item(contentType = "notice") {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                "Large bank: previewing the first ${state.questions.size} of " +
                                    "${state.totalQuestionCount} questions. Edits and deletions apply " +
                                    "to the previewed ones; the rest import exactly as they are in the file.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                item(contentType = "list-header") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "New questions (${newQuestions.size}) — will be added",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                }
                // These were all filed under "Skipped rows" in error red, next
                // to a second red list saying the import is fine. Most are
                // advisory and nothing was skipped, so the two are separated:
                // a real drop in red, a note in neutral.
                val warningGroups = com.mcqapp.domain.splitImportWarnings(parseWarnings)
                val skippedNotes = warningGroups.skipped
                val advisoryNotes = warningGroups.notes
                if (skippedNotes.isNotEmpty()) {
                    item(contentType = "list-header") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Skipped (${skippedNotes.size}) — content dropped from the file",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    // Keyed by position, not message: a real bank can carry the
                    // same advisory text many times (eight identical equation
                    // notes crashed the preview with a duplicate-key
                    // IllegalArgumentException), and these lists never reorder.
                    itemsIndexed(skippedNotes.take(20), key = { i, _ -> "skip|$i" }, contentType = { _, _ -> "warning" }) { _, warning ->
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
                    if (skippedNotes.size > 20) {
                        item(contentType = "more") {
                            Text(
                                "+${skippedNotes.size - 20} more skipped",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                if (advisoryNotes.isNotEmpty()) {
                    item(contentType = "list-header") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Notes from the file (${advisoryNotes.size}) — nothing was dropped",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    itemsIndexed(advisoryNotes.take(20), key = { i, _ -> "note|$i" }, contentType = { _, _ -> "warning" }) { _, note ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    if (advisoryNotes.size > 20) {
                        item(contentType = "more") {
                            Text(
                                "+${advisoryNotes.size - 20} more notes",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                if (warnings.isNotEmpty()) {
                    item(contentType = "list-header") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Warnings (${warnings.size}) — imports fine, but check these",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    itemsIndexed(warnings.take(20), key = { i, w -> "warn|${w.questionId}|$i" }, contentType = { _, _ -> "warning" }) { _, warning ->
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
                        item(contentType = "more") {
                            Text(
                                "+${warnings.size - 20} more warnings",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                items(newQuestions, key = { it.id }, contentType = { "preview-question" }) { question ->
                    PreviewQuestionCard(
                        question = question,
                        dimmed = false,
                        badge = null,
                        onDelete = { viewModel.deleteQuestion(question.id) },
                        onEdit = { editQuestion(question) }
                    )
                }

                if (changedQuestions.isNotEmpty()) {
                    item(contentType = "list-header") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Changed (${changedQuestions.size}) — will update the existing question",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    items(changedQuestions, key = { it.id }, contentType = { "preview-question" }) { question ->
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
                    item(contentType = "list-header") {
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

                    items(dupQuestions, key = { it.id }, contentType = { "preview-question" }) { question ->
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

            if (state.importing && state.previewTruncated) {
                LinearProgressIndicator(
                    progress = {
                        if (state.totalQuestionCount <= 0) {
                            0f
                        } else {
                            (state.importedQuestions.toFloat() / state.totalQuestionCount)
                                .coerceIn(0f, 1f)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Importing ${state.importedQuestions} of ${state.totalQuestionCount} questions…",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.import() },
                modifier = Modifier.fillMaxWidth(),
                enabled = importAction.enabled
            ) {
                if (state.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(importAction.label)
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
                // The full element renderer, not the flattened text: a DOCX
                // question can carry a table (or a formula) in its body,
                // and the preview must show what will actually import.
                ContentElements(
                    question.elements,
                    textStyle = MaterialTheme.typography.bodyMedium,
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

/**
 * A text field that suggests existing names as you type but accepts a new
 * one: tapping a row fills the field, typing whatever else just becomes the
 * value. A hard dropdown would force re-imports to invent near-duplicate
 * paper and category names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>
) {
    var expanded by remember { mutableStateOf(false) }
    val matches = remember(value, suggestions) { suggestNames(value, suggestions) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            matches.forEach { match ->
                DropdownMenuItem(
                    text = { Text(match) },
                    onClick = {
                        onValueChange(match)
                        expanded = false
                    }
                )
            }
        }
    }
}
