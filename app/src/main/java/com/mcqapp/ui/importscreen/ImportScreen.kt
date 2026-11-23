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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.mcqapp.data.io.ImportNote
import com.mcqapp.data.io.ImportReport
import com.mcqapp.data.io.normalizedFlashcard
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.ImportWarnings
import com.mcqapp.domain.hasVisibleContent
import com.mcqapp.ui.common.CompletionActions
import com.mcqapp.ui.common.LocalAnnouncer
import com.mcqapp.util.ContentElements
import com.mcqapp.util.Logger
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

/**
 * The display form of a persisted title. Fallback-title tokens stay canonical
 * in state and in the file the importer writes (SQL/search compare them by
 * equality); only what the learner reads is resolved here, through the
 * composition's locale-aware resources so an in-app language choice applies on
 * every API level. Editing the field writes the user's text, not this display
 * value, so an untouched fallback still imports as its canonical token.
 */
@Composable
internal fun displayTitle(raw: String): String =
    fallbackTitleRes(raw)?.let { stringResource(it) } ?: raw

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    importBytes: ByteArray?,
    importUri: android.net.Uri?,
    navController: NavController,
    viewModel: ImportViewModel = viewModel(key = "import-direct")
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A fatal parse/IO error shows a blocking dialog; announce it as well so a
    // screen reader is told why the import stopped.
    val announcer = LocalAnnouncer.current
    LaunchedEffect(state.error) {
        state.error?.let { announcer.announce(it) }
    }

    // Memoized: these re-ran over all rows on every recomposition (frames
    // dropped while scrolling big previews).
    //
    // The rows shown are the rows the importer will write. A one-option question
    // is already a flashcard by then — its only option became the back — so the
    // preview computes that same shape before it decides the badge or renders
    // anything; the raw DTO showed an option row and no FLASHCARD badge for a
    // row that imports as a card. Ids are untouched, so a checkpoint
    // (changed/duplicate) row classifies exactly as before.
    val previewQuestions = remember(state.questions) { previewQuestionsOf(state.questions) }
    val newQuestions = remember(
        previewQuestions, state.duplicateIds, state.changedIds, state.answerRefreshIds
    ) {
        previewQuestions.filter { it.id !in state.duplicateIds && it.id !in state.changedIds }
            .filter { it.id !in state.answerRefreshIds }
    }
    // An answer refresh is an in-place rewrite too, so it is shown beside the
    // plain changed rows — with its own badge saying progress will reset.
    val changedQuestions = remember(previewQuestions, state.changedIds, state.answerRefreshIds) {
        previewQuestions.filter {
            it.id in state.changedIds || it.id in state.answerRefreshIds
        }
    }
    val dupQuestions = remember(previewQuestions, state.duplicateIds) {
        previewQuestions.filter { it.id in state.duplicateIds }
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
            text = { ImportReportDetails(report) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeImportResult()
                    navController.navigate("library") {
                        popUpTo("library") { inclusive = true }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                // Same follow-up actions as the Library Anki report. The VM
                // now publishes the paper the import created/updated, so an
                // import can go straight to studying or testing it instead of
                // only returning to the library.
                CompletionActions(
                    paperId = state.importedPaperId,
                    onStudy = { id ->
                        viewModel.consumeImportResult()
                        navController.navigate(com.mcqapp.ui.navigation.studyRoute(id))
                    },
                    onTest = { id ->
                        viewModel.consumeImportResult()
                        navController.navigate(com.mcqapp.ui.navigation.testRoute(id))
                    },
                    onOpenPaper = { id ->
                        viewModel.consumeImportResult()
                        navController.navigate(com.mcqapp.ui.navigation.browseRoute(id))
                    },
                    onOpenLibrary = {
                        viewModel.consumeImportResult()
                        navController.navigate("library") {
                            popUpTo("library") { inclusive = true }
                        }
                    }
                )
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
            // The editor shows the shape the import writes, like the row it was
            // opened from: a one-option question is a card, not a choice.
            reader = { id ->
                viewModel.state.value.questions.find { it.id == id }?.normalizedFlashcard()
            },
            writer = { dto -> viewModel.updateQuestionFromImport(dto) }
        )
        viewModel.markEditing(question.id)
        com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = question
        Logger.d("IMPORTSCREEN", "holder armed: id=${question.id}; navigating to editor")
        navController.navigate(
            com.mcqapp.ui.navigation.editorRoute(
                question.id,
                "",
                "",
                // State the import source explicitly: it is what keeps the
                // editor from loading a same-id database row after process
                // death (question_editor_audit.md H-04).
                source = com.mcqapp.ui.editor.EditorSource.token(
                    com.mcqapp.ui.editor.EditorSource.IMPORT
                )
            )
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
                        value = displayTitle(state.paperTitle),
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
                            // The typed text lives in the state (durationMinutesText);
                            // rendering from the parsed value blanked the field under
                            // the first keystroke, so "0.5" was un-typeable.
                            value = state.durationMinutesText,
                            onValueChange = viewModel::updateDurationText,
                            label = { Text(stringResource(R.string.duration_min)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.negativeMarkingText,
                            onValueChange = viewModel::updateNegativeMarkingText,
                            label = { Text(stringResource(R.string.negative_marking)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SuggestionTextField(
                        label = stringResource(R.string.category_name_for_all_questions),
                        value = displayTitle(state.categoryName),
                        onValueChange = viewModel::updateCategoryName,
                        suggestions = state.categorySuggestions
                    )
                }

                // Informational only: it never blocks the import button. Shown
                // when the picker classified the file as an Anki deck, since a
                // deck's own scheduling memory is not carried over.
                if (ankiMemoryDisclosureApplies(state.importSource)) {
                    item(contentType = "anki-notice") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.anki_memory_notice_title),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.anki_memory_notice_body),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
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
                                stringResource(
                                    R.string.import_large_bank_notice,
                                    state.questions.size, state.totalQuestionCount
                                ),
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
                        pluralStringResource(
                            R.plurals.import_preview_new_count, newQuestions.size, newQuestions.size
                        ),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                }
                // These were all filed under "Skipped rows" in error red, next
                // to a second red list saying the import is fine. Most are
                // advisory and nothing was skipped, so the two are separated:
                // a real drop in red, a note in neutral.
                val warningGroups = com.mcqapp.domain.splitTypedImportWarnings(parseWarnings)
                val skippedNotes = warningGroups.skipped
                val advisoryNotes = warningGroups.notes
                if (skippedNotes.isNotEmpty()) {
                    item(contentType = "list-header") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.import_preview_skipped_count, skippedNotes.size),
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
                                stringResource(R.string.import_more_skipped, skippedNotes.size - 20),
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
                            stringResource(R.string.import_preview_notes_count, advisoryNotes.size),
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
                                pluralStringResource(R.plurals.import_more_notes, advisoryNotes.size - 20, advisoryNotes.size - 20),
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
                            stringResource(R.string.import_preview_warnings_count, warnings.size),
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
                                pluralStringResource(R.plurals.import_more_warnings, warnings.size - 20, warnings.size - 20),
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
                        badges = previewBadges(question),
                        onDelete = { viewModel.deleteQuestion(question.id) },
                        onEdit = { editQuestion(question) }
                    )
                }

                if (changedQuestions.isNotEmpty()) {
                    item(contentType = "list-header") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.import_preview_changed_count, changedQuestions.size),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    items(changedQuestions, key = { it.id }, contentType = { "preview-question" }) { question ->
                        PreviewQuestionCard(
                            question = question,
                            dimmed = false,
                            badges = changedPreviewBadges(
                                isFlashcard = question.options.isEmpty(),
                                answerRefresh = question.id in state.answerRefreshIds,
                                scheduleReset = question.id in state.scheduleResetIds
                            ),
                            onDelete = { viewModel.deleteQuestion(question.id) },
                            onEdit = { editQuestion(question) }
                        )
                    }
                }

                if (dupQuestions.isNotEmpty()) {
                    item(contentType = "list-header") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.import_preview_duplicate_count, dupQuestions.size),
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
                            badges = previewBadges(question, R.string.r2_import_badge_duplicate),
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
                    stringResource(
                        R.string.import_importing_progress,
                        state.importedQuestions, state.totalQuestionCount
                    ),
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

/** Stable test tag for one report line, derived from its kind. */
internal fun importReportLineTag(kind: ImportReportLineKind): String =
    "import-report-line-${kind.name.lowercase()}"

/** Tag for the "progress restore was skipped" notice under the counted lines. */
internal const val IMPORT_REPORT_NOTICE_TAG = "import-report-notice"

/** Tag for the line shown when a report has no counted line at all. */
internal const val IMPORT_REPORT_EMPTY_TAG = "import-report-empty"

/** Tag for the "Anki import notes" block under the counted lines. */
internal const val IMPORT_REPORT_ANKI_NOTES_TAG = "import-report-anki-notes"

/** Pure mapping from a report note to its copy, so the reasons are testable. */
internal fun importNoteRes(note: ImportNote): Pair<Int, List<Any>> = when (note) {
    is ImportNote.AnkiDroppedCards ->
        R.string.data_honesty_anki_note_blank to listOf(note.count)
    ImportNote.AnkiMissingMedia -> R.string.data_honesty_anki_note_media to emptyList()
}

/**
 * The completion report, one plural-aware line per non-zero count. Backed by
 * [importReportLines] so what the user reads is the tested enumeration — in
 * particular the restored schedules and paper counts a backup restore earns.
 *
 * A restore can carry all eleven lines plus the notice, and Material3 puts this
 * in AlertDialog's height-constrained text slot: laid out as loose siblings they
 * drew over each other and over the buttons. The column scrolls and spaces its
 * lines so any report length stays inside the dialog and remains readable.
 *
 * [emptyMessageRes] covers the one case with nothing to enumerate: an Anki
 * deck that yielded no question. Without it the dialog's text slot is blank,
 * which reads as a silent failure; the caller supplies the sentence to say
 * instead. The resource id, not resolved text, keeps the call inside a `Text`.
 */
@Composable
internal fun ImportReportDetails(report: ImportReport, emptyMessageRes: Int? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val lines = importReportLines(report)
        if (lines.isEmpty() && emptyMessageRes != null) {
            Text(
                text = stringResource(emptyMessageRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(IMPORT_REPORT_EMPTY_TAG)
            )
        }
        lines.forEach { line ->
            val resId = when (line.kind) {
                ImportReportLineKind.NEW_PAPERS -> R.plurals.followup_import_new_papers
                ImportReportLineKind.UPDATED_PAPERS -> R.plurals.followup_import_updated_papers
                ImportReportLineKind.NEW_QUESTIONS -> R.plurals.followup_import_new_questions
                ImportReportLineKind.UPDATED_QUESTIONS -> R.plurals.followup_import_updated_questions
                ImportReportLineKind.DUPLICATE_QUESTIONS -> R.plurals.followup_import_duplicate_questions
                ImportReportLineKind.ANSWERS_REFRESHED -> R.plurals.followup_import_answers_refreshed
                ImportReportLineKind.SCHEDULES_RESET -> R.plurals.data_honesty_schedules_reset
                ImportReportLineKind.RESTORED_SCHEDULES -> R.plurals.followup_import_restored_schedules
                ImportReportLineKind.RESTORED_BOOKMARKS -> R.plurals.followup_import_restored_bookmarks
                ImportReportLineKind.RESTORED_ATTEMPTS -> R.plurals.followup_import_restored_attempts
                ImportReportLineKind.DROPPED_ANKI_STEMS -> R.plurals.followup_import_dropped_stems
                ImportReportLineKind.RECALL_CONVERTED -> R.plurals.followup_import_recall_converted
                ImportReportLineKind.ROWS_SKIPPED -> R.plurals.data_honesty_rows_skipped
            }
            Text(
                text = pluralStringResource(resId, line.count, line.count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(importReportLineTag(line.kind))
            )
        }
        // Anki decks lose cards and sometimes pictures on the way in. The
        // reader's reasons used to stop at the log; the learner now sees them
        // beside the counts instead of assuming the deck came over verbatim.
        if (report.importNotes.isNotEmpty()) {
            Text(
                text = stringResource(R.string.data_honesty_anki_notes_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(IMPORT_REPORT_ANKI_NOTES_TAG)
            )
            report.importNotes.forEach { note ->
                val (resId, args) = importNoteRes(note)
                Text(
                    text = stringResource(resId, *args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        // I3: a restore that hit its progress budget still imported every
        // question. Say so plainly instead of implying a complete restore.
        importReportNotice(report)?.let { notice ->
            Text(
                text = stringResource(notice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(IMPORT_REPORT_NOTICE_TAG)
            )
        }
    }
}

/**
 * The preview's rows, in the shape the importer will store them: the one shared
 * conversion (`normalizedFlashcard`) that the importers, the content hash and
 * [com.mcqapp.domain.ImportWarnings] already apply. A one-option question is a
 * flashcard whose option became the back, so the preview must not draw it as a
 * multiple-choice row with an option and no FLASHCARD badge — that row would
 * contradict what actually gets imported.
 *
 * Ids are preserved for a file's own ids, which is what keeps the
 * changed/duplicate classification (id-keyed, computed from the normalised
 * hash) working unchanged. A *generated* id becomes the prompt-only one, so two
 * legacy one-option rows with the same front and different single answers now
 * share an id — and the import writes by id, the later row replacing the
 * earlier. The preview must show that one row too (last wins, first position):
 * a list keyed by id cannot hold both, and claiming two options are coming when
 * the store keeps one is the same "preview lies about the import" defect.
 */
internal fun previewQuestionsOf(
    questions: List<com.mcqapp.data.io.QuestionDto>
): List<com.mcqapp.data.io.QuestionDto> {
    val byId = LinkedHashMap<String, com.mcqapp.data.io.QuestionDto>(questions.size)
    for (question in questions) {
        val stored = question.normalizedFlashcard()
        byId[stored.id] = stored
    }
    return byId.values.toList()
}

/**
 * The badges a preview row shows, as string resources in display order.
 *
 * FLASHCARD is a property of the question (zero options), not a state of the
 * import, so it is not one slot competing with CHANGED/DUPLICATE: a card can be
 * either, and hiding the type would leave the row with no explanation for its
 * empty option list. Both are therefore shown, the type first — CHANGED does
 * not win, because the reader needs to see that the row is a card before
 * judging what updating it will do.
 *
 * `internal` so the order is pinned by a real unit test, not only by a source
 * shape guard.
 */
internal fun previewBadges(
    question: com.mcqapp.data.io.QuestionDto,
    @androidx.annotation.StringRes vararg status: Int
): List<Int> = buildList {
    if (question.options.isEmpty()) add(R.string.r2_import_badge_flashcard)
    addAll(status.toList())
}

/**
 * Badges for a Changed row. The two data-honesty flags are additive: a row can
 * refresh its answer AND reset a study schedule, and hiding either would leave
 * the learner agreeing to a loss they were never told about. The flashcard type
 * still leads, matching [previewBadges].
 */
internal fun changedPreviewBadges(
    isFlashcard: Boolean,
    answerRefresh: Boolean,
    scheduleReset: Boolean
): List<Int> = buildList {
    if (isFlashcard) add(R.string.r2_import_badge_flashcard)
    add(R.string.r2_import_badge_changed)
    if (answerRefresh) add(R.string.data_honesty_badge_answer_refresh)
    if (scheduleReset) add(R.string.data_honesty_badge_schedule_reset)
}

@Composable
private fun PreviewQuestionCard(
    question: com.mcqapp.data.io.QuestionDto,
    dimmed: Boolean,
    badges: List<Int>,
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
                if (badges.isNotEmpty()) {
                    badges.forEach { badge ->
                        Text(
                            stringResource(badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
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
            // A parsed DTO can carry a flattened explanation without element
            // blocks (an Anki marker back), so the text still counts as
            // visible alongside the content-aware check.
            val explanationVisible = question.explanation.isNotBlank() ||
                question.explanationElements.hasVisibleContent ||
                !question.explanationImage.isNullOrBlank()
            if (explanationVisible) {
                // The row is the shape the import writes, so a card's back is
                // called an Answer here just as Study and Browse do.
                val isCard = question.options.isEmpty()
                Text(
                    // A back that is only a picture, a table or a formula has
                    // no flattened text to append, so the heading falls back
                    // to the plain label.
                    if (question.explanation.isNotBlank()) {
                        stringResource(
                            if (isCard) R.string.r2_import_answer_label
                            else R.string.import_explanation_label,
                            displayTitle(question.explanation)
                        )
                    } else {
                        stringResource(
                            if (isCard) R.string.r2_study_answer_heading
                            else R.string.explanation
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // The flattened heading already reads the text runs out;
                // render only the blocks it cannot, so the preview shows a
                // table/formula/image back without printing text twice.
                val richExplanation =
                    question.explanationElements.filterNot { it is ContentElement.TextElement }
                if (richExplanation.isNotEmpty()) {
                    ContentElements(
                        richExplanation,
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
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
internal fun SuggestionTextField(
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
                // Editable anchor: opening the menu must not steal focus from
                // the field, otherwise a name not in the suggestions cannot be
                // typed (PrimaryNotEditable moves focus to the menu).
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
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
