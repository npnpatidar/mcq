package com.mcqapp.ui.history

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.mcqapp.R
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.export.PaperExporter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Paper
import com.mcqapp.domain.isFlashcard
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.PagingAppendErrorItem
import com.mcqapp.ui.common.PagingRefreshErrorItem
import com.mcqapp.ui.common.ScopeModalSheet
import com.mcqapp.ui.common.ScopeSheetContent
import com.mcqapp.ui.common.ScopeStep
import com.mcqapp.ui.common.ScopeTrigger
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.common.UiMessageTone
import com.mcqapp.util.ContentElements

/** The two sheet steps, kept saveable in [BookmarksScreen] across rotation. */
internal typealias BookmarksScopeStep = ScopeStep

/**
 * Above the list: one button for *which bookmarks* are shown. Tapping it opens
 * the shared paper → categories sheet; a selection applies immediately.
 */
@Composable
internal fun BookmarksScopeTrigger(
    state: BookmarksUiState,
    papers: List<Paper>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val paper = papers.firstOrNull { it.id == state.filterPaperId }
    // ScopeTrigger has no modifier slot; the wrapper supplies the screen's
    // 16 dp horizontal gutter without changing the shared API.
    Box(modifier) {
        ScopeTrigger(
            paper = paper,
            selectedCategoryIds = state.categoryFilterIds,
            tag = "bookmarks-scope-button",
            onClick = onClick
        )
    }
}

/** Modal host, delegating to the shared two-step sheet with Bookmarks' tags. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksScopeSheet(
    papers: List<Paper>,
    selectedCategoryIds: Set<String>,
    step: ScopeStep,
    chosenPaperId: String?,
    onChoosePaper: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onClearCategoriesInPaper: () -> Unit,
    onClearScope: () -> Unit,
    onBackToPapers: () -> Unit,
    onDismiss: () -> Unit
) {
    ScopeModalSheet(
        papers = papers,
        selectedCategoryIds = selectedCategoryIds,
        step = step,
        chosenPaperId = chosenPaperId,
        tagPrefix = "bookmarks-scope",
        onChoosePaper = onChoosePaper,
        onToggleCategory = onToggleCategory,
        onClearCategoriesInPaper = onClearCategoriesInPaper,
        onClearScope = onClearScope,
        onBackToPapers = onBackToPapers,
        onDismiss = onDismiss
    )
}

/** Stateless two-step body for UI tests, with the same [tagPrefix] as the host. */
@Composable
internal fun BookmarksScopeSheetContent(
    papers: List<Paper>,
    selectedCategoryIds: Set<String>,
    step: ScopeStep,
    chosenPaperId: String?,
    onChoosePaper: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onClearCategoriesInPaper: () -> Unit,
    onClearScope: () -> Unit,
    onBackToPapers: () -> Unit,
    onDismiss: () -> Unit
) {
    ScopeSheetContent(
        papers = papers,
        selectedCategoryIds = selectedCategoryIds,
        step = step,
        chosenPaperId = chosenPaperId,
        tagPrefix = "bookmarks-scope",
        onChoosePaper = onChoosePaper,
        onToggleCategory = onToggleCategory,
        onClearCategoriesInPaper = onClearCategoriesInPaper,
        onClearScope = onClearScope,
        onBackToPapers = onBackToPapers,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: BookmarksViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportError by viewModel.exportError.collectAsStateWithLifecycle()
    val papers by viewModel.papers.collectAsStateWithLifecycle()
    val paperTitles by viewModel.paperTitles.collectAsStateWithLifecycle()
    val items = viewModel.pagingData.collectAsLazyPagingItems()
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    // A scope change hides rows; a selection made under the old scope could
    // then remove bookmarks the user can no longer see. Drop it wholesale.
    // Key on the WHOLE applied scope — a category change is a scope change too.
    LaunchedEffect(state.filterPaperId, state.categoryFilterIds) {
        if (selectionMode) {
            selectionMode = false
            selectedIds = emptySet()
        }
    }

    // Sheet choreography is view state: which step, and which paper step 2 is
    // about. Both survive rotation. Only the opening event seeds them — an
    // effect keyed on showScopeSheet also ran on rotation and sent a sheet the
    // user had backed to step 1 straight back to step 2.
    var showScopeSheet by rememberSaveable { mutableStateOf(false) }
    var scopeStep by rememberSaveable { mutableStateOf(BookmarksScopeStep.PAPERS) }
    var chosenPaperId by rememberSaveable { mutableStateOf<String?>(null) }

    val showFormatDialog = state.showFormatDialog
    val exportFormat = state.exportFormat
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    val platformContext = androidx.compose.ui.platform.LocalContext.current
    // LocalResources (not LocalContext.current) keeps the chooser title
    // configuration-aware: LocalContext.getString() is not re-read when the
    // Configuration changes (LocalContextGetResourceValueCall).
    val platformResources = LocalResources.current
    val sendMessage = LocalUiMessageSender.current
    val shareNoAppText = stringResource(R.string.share_no_app)
    // Resolved in composition: UiMessage.onAction is not composable, and the
    // snackbar must show the same copy on every row.
    val bookmarkRemovedText = stringResource(R.string.bookmark_removed_question_kept)
    val undoLabel = stringResource(R.string.undo)
    // rememberSaveable: the system save dialog recreates the activity, and a
    // plain `remember` came back null so the re-delivered URI was dropped
    // (consolidated_bugs.md F4). Enums are Serializable and save as-is.
    var pendingFormat by rememberSaveable { mutableStateOf<ExportFormat?>(null) }

    // R1: the screen only starts the chooser; the ViewModel owns the render so
    // rotation/Back cannot cancel the write before startActivity.
    val shareEvent by viewModel.shareEvents.latest.collectAsStateWithLifecycle()
    LaunchedEffect(shareEvent) {
        val event = shareEvent ?: return@LaunchedEffect
        try {
            platformContext.startActivity(
                Intent.createChooser(
                    event.value,
                    platformResources.getString(R.string.share_chooser_title)
                )
            )
        } catch (_: ActivityNotFoundException) {
            sendMessage(UiMessage(shareNoAppText, UiMessageTone.Error))
        }
        viewModel.shareEvents.consume(event.id)
    }

    fun onExportDocument(uri: Uri?) {
        val format = pendingFormat
        pendingFormat = null
        if (uri != null && format != null) viewModel.exportBookmarks(uri, format)
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JSON_INLINE.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.ZIP.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportHtmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.HTML.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.PDF.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportApkgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.APKG.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportDocxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.DOCX.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }

    fun exportSaver(format: ExportFormat) = when (format) {
        ExportFormat.JSON_INLINE -> exportJsonLauncher
        ExportFormat.ZIP -> exportZipLauncher
        ExportFormat.HTML, ExportFormat.HTML_QUIZ -> exportHtmlLauncher
        ExportFormat.PDF, ExportFormat.PDF_ANSWER_KEY -> exportPdfLauncher
        ExportFormat.APKG -> exportApkgLauncher
        ExportFormat.DOCX -> exportDocxLauncher
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bookmarks)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) {
                            selectionMode = false
                            selectedIds = emptySet()
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        Text(
                            stringResource(R.string.bookmarks_selected_count, selectedIds.size),
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                viewModel.removeBookmarks(selectedIds) { removed ->
                                    if (removed > 0) {
                                        sendMessage(
                                            UiMessage(
                                                text = platformResources.getQuantityString(
                                                    R.plurals.bookmarks_removed_count, removed, removed
                                                ),
                                                tone = UiMessageTone.Info,
                                                actionLabel = undoLabel,
                                                onAction = { viewModel.undoBulkRemove {} }
                                            )
                                        )
                                    }
                                }
                                selectionMode = false
                                selectedIds = emptySet()
                            }
                        ) { Text(stringResource(R.string.bookmarks_remove_selected)) }
                    } else {
                        // Both actions are gated on the SCOPED count: with no
                        // bookmarks in this scope there is nothing to select or
                        // share, even if other papers hold bookmarks.
                        if (state.count > 0) {
                            TextButton(onClick = {
                                selectionMode = true
                                selectedIds = emptySet()
                            }) { Text(stringResource(R.string.bookmarks_select)) }
                            IconButton(onClick = { viewModel.openFormatDialog() }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.export_bookmarks))
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        // A failed refresh rendered as an empty or blank list with no way
        // back (consolidated_bugs.md F6).
        val refreshFailed = items.loadState.refresh is LoadState.Error
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The scope trigger stays visible in EVERY state, including the
            // empty one and before papers load: a scope with no hits must still
            // be changeable. Horizontal padding matches the list's 16 dp content
            // padding, as on Search and Passages.
            BookmarksScopeTrigger(
                state = state,
                papers = papers,
                onClick = {
                    val applied = state.filterPaperId
                    chosenPaperId = applied
                    scopeStep = if (applied != null) {
                        BookmarksScopeStep.CATEGORIES
                    } else {
                        BookmarksScopeStep.PAPERS
                    }
                    showScopeSheet = true
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            when {
                !state.loaded -> {
                    // R7: the COUNT Flow has not emitted yet; showing the empty
                    // state here made real bookmarks flash an "empty" screen on entry.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                state.count == 0 && !refreshFailed -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(
                                    if (state.hasAnyBookmarks) R.string.no_bookmarks_in_scope
                                    else R.string.no_bookmarked_questions
                                )
                            )
                            // A scoped-empty view offers the one-tap way back to
                            // the whole reading list, not just a dead-end message.
                            if (state.hasAnyBookmarks) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { viewModel.clearScopeFilters() }) {
                                    Text(stringResource(R.string.bookmarks_all_papers))
                                }
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            count = items.itemCount,
                            key = items.itemKey { it.question.id },
                            contentType = { "bookmark" }
                        ) { index ->
                            val bookmarked = items[index] ?: return@items
                            val question = bookmarked.question
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selectionMode) {
                                            selectedIds = selectedIds.toMutableSet().apply {
                                                if (!add(question.id)) remove(question.id)
                                            }
                                        } else {
                                            // Bookmarks are a reading list: opening one
                                            // should show the question in context, not
                                            // drop straight into the editor.
                                            navController.navigate(
                                                com.mcqapp.ui.navigation.browseRoute(
                                                    paperId = bookmarked.paperId,
                                                    focusQuestionId = question.id
                                                )
                                            )
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selectionMode) {
                                        Checkbox(
                                            checked = question.id in selectedIds,
                                            onCheckedChange = {
                                                selectedIds = selectedIds.toMutableSet().apply {
                                                    if (!add(question.id)) remove(question.id)
                                                }
                                            }
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        // A card's type, shown as text: its front is
                                        // the question and it has no options, which the
                                        // label explains. The back stays hidden — this
                                        // list is a reading list of fronts.
                                        if (question.isFlashcard) {
                                            Text(
                                                stringResource(R.string.r2_flashcard_label),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                        ContentElements(
                                            question.elements,
                                            textStyle = MaterialTheme.typography.bodyMedium,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // Where the card lives, so a reading list
                                        // collected across papers stays navigable.
                                        paperTitles[bookmarked.paperId]?.let { title ->
                                            Text(
                                                stringResource(R.string.bookmark_provenance, title),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        // Deliberately no explanation here, image
                                        // included. A bookmark list is a reading list
                                        // of questions; with no options shown, the
                                        // explanation gave the answer away against
                                        // half a question.
                                    }
                                    if (!selectionMode) IconButton(
                                        onClick = {
                                            // The action drops the bookmark, not the
                                            // question: the owner tapped this trash
                                            // can expecting a deletion and found the
                                            // question still in Study. Say so, and
                                            // offer the way back.
                                            viewModel.removeBookmark(question.id)
                                            sendMessage(
                                                UiMessage(
                                                    text = bookmarkRemovedText,
                                                    tone = UiMessageTone.Info,
                                                    actionLabel = undoLabel,
                                                    onAction = { viewModel.restoreBookmark(question.id) }
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.BookmarkRemove,
                                            contentDescription = stringResource(R.string.remove_bookmark)
                                        )
                                    }
                                }
                            }
                        }
                        if (items.loadState.append is LoadState.Loading) {
                            item(key = "bookmarks-load-more", contentType = "load-more") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        if (items.loadState.append is LoadState.Error) {
                            item(key = "bookmarks-append-error", contentType = "append-error") {
                                PagingAppendErrorItem(items)
                            }
                        }
                        if (refreshFailed) {
                            item(key = "bookmarks-refresh-error", contentType = "refresh-error") {
                                PagingRefreshErrorItem(items)
                            }
                        }
                    }
                }
            }
        }
    }

    exportError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    if (showFormatDialog) {
        fun closeFormatDialog() {
            pendingFormat = null
            viewModel.closeFormatDialog()
        }

        fun saveBookmarksToFile() {
            pendingFormat = exportFormat
            viewModel.closeFormatDialog()
            exportSaver(exportFormat).launch(
                PaperExporter.fileNameFor(
                    com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                    exportFormat
                )
            )
        }

        AlertDialog(
            // R8: a share render must not be dismissed out from under the user;
            // rotation keeps the dialog because its state lives in the VM.
            onDismissRequest = { if (!isSharing) closeFormatDialog() },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.export_bookmarked_questions,
                        state.count,
                        state.count
                    )
                )
            },
            text = {
                // Eight formats with their caveat text overflow a phone screen;
                // a bounded scrolling column keeps every row selectable.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (isSharing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sharing))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    ExportFormat.entries.forEach { format ->
                        val title = stringResource(format.titleRes)
                        // stringResource is @Composable, so resolve both pieces in
                        // composition and hand the helper already-localized values.
                        val baseDescription = stringResource(format.descriptionRes)
                        val caveat = if (format.contentOnlyNoteRes != 0) {
                            stringResource(format.contentOnlyNoteRes)
                        } else ""
                        val description = format.descriptionWithCaveat { id ->
                            if (id == format.contentOnlyNoteRes) caveat else baseDescription
                        }
                        val isSelected = exportFormat == format
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("export-format-${format.name}")
                                .clickable(
                                    enabled = !isSharing,
                                    onClickLabel = title,
                                    role = Role.RadioButton
                                ) { viewModel.selectFormat(format) }
                                .semantics {
                                    contentDescription = title
                                    selected = isSelected
                                    // Spoken checked state, not just the radio glyph.
                                    stateDescription = platformResources.getString(
                                        if (isSelected) R.string.r2_res_format_selected
                                        else R.string.r2_res_format_not_selected
                                    )
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.selectFormat(format) },
                                enabled = !isSharing
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = !isSharing, onClick = { saveBookmarksToFile() }) {
                        Text(stringResource(R.string.share_save))
                    }
                    TextButton(
                        enabled = !isSharing,
                        onClick = { viewModel.shareBookmarks(exportFormat) }
                    ) {
                        Text(stringResource(R.string.share_share))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isSharing, onClick = { closeFormatDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showScopeSheet) {
        BookmarksScopeSheet(
            papers = papers,
            selectedCategoryIds = state.categoryFilterIds,
            step = scopeStep,
            chosenPaperId = chosenPaperId,
            onChoosePaper = { paperId ->
                viewModel.choosePaper(paperId)
                chosenPaperId = paperId
                scopeStep = BookmarksScopeStep.CATEGORIES
            },
            onToggleCategory = viewModel::toggleCategory,
            onClearCategoriesInPaper = viewModel::clearCategoriesInPaper,
            onClearScope = viewModel::clearScopeFilters,
            onBackToPapers = { scopeStep = BookmarksScopeStep.PAPERS },
            onDismiss = { showScopeSheet = false }
        )
    }
}
