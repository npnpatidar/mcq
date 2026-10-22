package com.mcqapp.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.R
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.export.PaperExporter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger
import com.mcqapp.util.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: LibraryViewModel = viewModel()
) {
    val papers by viewModel.papers.collectAsStateWithLifecycle()
    val mistakeCounts by viewModel.mistakeCounts.collectAsStateWithLifecycle()
    val studyCounts by viewModel.studyCounts.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val exportError by viewModel.exportError.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val importReport by viewModel.importReport.collectAsStateWithLifecycle()
    val importReportTitle by viewModel.importReportTitle.collectAsStateWithLifecycle()
    // Due counts have to be re-read when the library resumes, not when Study is
    // tapped. A study session rewrites the schedules while the library is off
    // screen, so a refresh fired from the click ran *before* anything changed and
    // left the badge one navigation stale — the number only caught up after the
    // user tapped Study a second time and left again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStudyCounts()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showPaperDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryDialogPaperId by remember { mutableStateOf("") }
    var categoryDialogParentId by remember { mutableStateOf<String?>(null) }
    var editingCategory by remember { mutableStateOf<com.mcqapp.domain.CategoryNode?>(null) }
    var pendingExportPaperId by remember { mutableStateOf<String?>(null) }
    // (paperId, ticked branches, branch count): Export follows the selection
    // when something is ticked, and the count is for the dialog's title only.
    var pendingExportSelection by remember { mutableStateOf<Triple<String, List<String>, Int>?>(null) }
    var pendingExportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ExportFormat.JSON_INLINE) }

    // The format must travel in state: saver callbacks fire after the save
    // dialog, so anything baked into the callback itself goes stale.
    fun onExportDocument(uri: Uri?) {
        val paperId = pendingExportPaperId
        val selection = pendingExportSelection
        val format = pendingExportFormat
        pendingExportPaperId = null
        pendingExportSelection = null
        pendingExportFormat = null
        uri?.let {
            if (selection != null && format != null) {
                viewModel.exportSelectionAs(it, selection.first, selection.second, format)
            } else if (paperId != null && format != null) {
                viewModel.exportPaperAs(it, paperId, format)
            } else if (paperId == null && selection == null) {
                viewModel.exportAll(it)
            }
        }
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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val picked = uri ?: return@rememberLauncherForActivityResult
        // Off the main thread and size-capped: this used to run in the
        // picker callback with readBytes(), so a large pick allocated the
        // whole file on the UI thread and ANR'd or died on OOM, which
        // catch (Exception) does not cover.
        scope.launch {
            // Read a bounded prefix first. It is enough to tell a zip from
            // JSON and to keep a small bank in memory, but it never allocates
            // the whole large bank: a bigger JSON is streamed from the Uri.
            val prefix = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(picked)?.use { stream ->
                        com.mcqapp.util.readPrefix(
                            stream,
                            com.mcqapp.ui.importscreen.JSON_STREAM_THRESHOLD_BYTES.toLong()
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e("LIB", "Failed to read import file", e)
                viewModel.showError("Could not read the file: ${e.message}")
                return@launch
            } ?: run {
                viewModel.showError("Could not read the file.")
                return@launch
            }

            // Route by content, not by name or MIME type (pickers report
            // both inconsistently). Both .apkg and .docx are zips, so a
            // .docx is told apart by its word/document.xml entry; our
            // JSON can arrive under any extension and stays on the text
            // path below.
            val isZip = prefix.bytes.size >= 2 && prefix.bytes[0] == 'P'.code.toByte() &&
                prefix.bytes[1] == 'K'.code.toByte()

            if (isZip) {
                // Classify by streaming the archive's entry names, so the file
                // is never read whole. Both .apkg and .docx are zips; a Word
                // file is told apart by its word/document.xml entry, a backup
                // by its manifest. A backup with images in it is often over the
                // old size cap — "too large to import" would be the wrong thing
                // to tell someone who picked a file that was never meant to be
                // imported at all.
                val kind = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(picked)?.use { stream ->
                            classifyPickedArchive(stream)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("LIB", "Failed to read import file", e)
                    viewModel.showError("Could not read the file: ${e.message}")
                    return@launch
                } ?: run {
                    viewModel.showError("Could not read the file.")
                    return@launch
                }
                when (kind) {
                    PickedArchive.DOCX -> {
                        // A Word bank is small and the parser wants its bytes,
                        // so read it now, under the same cap as before.
                        val bytes = try {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(picked)?.use { stream ->
                                    com.mcqapp.util.readBounded(stream)
                                }
                            }
                        } catch (e: Exception) {
                            Logger.e("LIB", "Failed to read import file", e)
                            viewModel.showError(
                                if (e is com.mcqapp.util.ImportTooLargeException) {
                                    com.mcqapp.util.tooLargeMessage(
                                        e.atLeastBytes,
                                        com.mcqapp.util.MAX_IMPORT_BYTES
                                    )
                                } else {
                                    "Could not read the file: ${e.message}"
                                }
                            )
                            return@launch
                        } ?: run {
                            viewModel.showError("Could not read the file.")
                            return@launch
                        }
                        Logger.i("LIB", "picked Word file: bytes=${bytes.size}")
                        com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxBytes = bytes
                        // The file name becomes the paper title (the document rarely
                        // names its own paper). The provider's display name, not the
                        // opaque row id a content Uri carries in lastPathSegment.
                        com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxName =
                            context.contentResolver.displayName(picked)
                        navController.navigate("import/direct")
                    }
                    PickedArchive.BACKUP -> {
                        // Restore is the reverse of export: it replaces the
                        // whole library, while import only adds papers.
                        Logger.i("LIB", "picked library backup: not an import")
                        viewModel.showError(backupNotABankMessage())
                    }
                    PickedArchive.OTHER -> {
                        // The deck is streamed straight from the Uri by the
                        // importer, so the archive is never held as bytes.
                        val title = context.contentResolver.displayName(picked) ?: "deck.apkg"
                        viewModel.importAnkiPackage(title) {
                            context.contentResolver.openInputStream(picked)
                        }
                    }
                }
                return@launch
            }

            if (prefix.truncated) {
                // Too large to hold as bytes: the import screen re-opens the
                // Uri, previews a bounded sample and streams the rest.
                Logger.i("LIB", "picked large import file: streaming from uri")
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonUri = picked
            } else {
                // Bytes stay bytes: the String copy that used to be made here
                // doubled the bank in memory before the parser ever saw it.
                val bytes = prefix.bytes
                val fingerprint = try {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                    digest.joinToString("") { "%02x".format(it) }.take(12)
                } catch (e: Exception) {
                    "unknown"
                }
                Logger.i("LIB", "picked import file: bytes=${bytes.size}, sha=$fingerprint")
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonBytes = bytes
            }
            navController.navigate("import/direct")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    papers = papers,
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    onOpenHistory = {
                        scope.launch { drawerState.close() }
                        navController.navigate("history")
                    },
                    onOpenStats = {
                        scope.launch { drawerState.close() }
                        navController.navigate("stats")
                    },
                    onOpenBookmarks = {
                        scope.launch { drawerState.close() }
                        navController.navigate("bookmarks")
                    },
                    onOpenSearch = {
                        scope.launch { drawerState.close() }
                        navController.navigate("search")
                    },
                    onOpenPassages = {
                        scope.launch { drawerState.close() }
                        navController.navigate("passages")
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        navController.navigate("settings")
                    },
                    onStartTest = { paperId, categoryIds ->
                        scope.launch { drawerState.close() }
                        Logger.i("LIB", "Drawer test: paperId=$paperId, categories=${categoryIds.size}")
                        navController.navigate(
                            com.mcqapp.ui.navigation.testRoute(paperId, categoryIds)
                        )
                    },
                    onImport = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "application/zip", "text/*", "*/*")) },
                    onStartStudy = { paperId, categoryIds ->
                        scope.launch { drawerState.close() }
                        Logger.i("LIB", "Drawer study: paperId=$paperId, categories=${categoryIds.size}")
                        navController.navigate(
                            com.mcqapp.ui.navigation.studyRoute(paperId, categoryIds)
                        )
                    },
                    selectedByPaper = selection,
                    onToggleCategory = viewModel::toggleCategory,
                    onExpandCategories = viewModel::ensureCategoryCounts,
                    countsByPaper = categoryCounts,
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.mcq_app)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.open_menu))
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showPaperDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_paper))
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Delayed empty-state: papers arrive a frame after first
                // composition, which used to flash "No papers yet" every visit.
                val showEmpty = com.mcqapp.util.rememberDelayedVisibility(papers.isEmpty())
                if (showEmpty) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.no_papers_yet),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadSampleData() }) {
                                Text(stringResource(R.string.load_sample_paper))
                            }
                        }
                    }
                } else if (papers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize()) { }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 88.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(papers, key = { it.id }) { paper ->
                            val dueCountBadge = studyCounts[paper.id]?.due ?: 0
                            val leechCountBadge = studyCounts[paper.id]?.leeches ?: 0
                            PaperCard(
                                paper = paper,
                                onStart = { navController.navigate(com.mcqapp.ui.navigation.testRoute(paper.id)) },
                                onPracticeMistakes = {
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.testRoute(paper.id, mistakes = true)
                                    )
                                },
                                onDrill = { count, minutes ->
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.testRoute(
                                            paper.id, drillCount = count, drillMinutes = minutes
                                        )
                                    )
                                },
                                onStudy = {
                                    Logger.i("LIB", "Study: paperId=${paper.id}, due=$dueCountBadge")
                                    navController.navigate(com.mcqapp.ui.navigation.studyRoute(paper.id))
                                },
                                onStudyLeeches = {
                                    Logger.i("LIB", "Study leeches: paperId=${paper.id}, leeches=$leechCountBadge")
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.studyRoute(paper.id, leechesOnly = true)
                                    )
                                },
                                dueCount = dueCountBadge,
                                freshCount = studyCounts[paper.id]?.fresh ?: 0,
                                leechCount = leechCountBadge,
                                mistakeCount = mistakeCounts[paper.id] ?: 0,
                                selected = selection[paper.id].orEmpty(),
                                onToggleCategory = { id -> viewModel.toggleCategory(paper.id, id) },
                                onStartScopedStudy = { ids ->
                                    Logger.i("LIB", "Card study: paperId=${paper.id}, categories=${ids.size}")
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.studyRoute(paper.id, ids)
                                    )
                                },
                                categoryCounts = categoryCounts[paper.id].orEmpty(),
                                onExpandCategories = { viewModel.ensureCategoryCounts(paper.id) },
                                onBrowse = {
                                    navController.navigate(com.mcqapp.ui.navigation.browseRoute(paper.id))
                                },
                                onScopedTest = { ids ->
                                    Logger.i("LIB", "Card test: paperId=${paper.id}, categories=${ids.size}")
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.testRoute(paper.id, ids)
                                    )
                                },
                                onExport = { tickedIds ->
                                    // Nothing ticked → the whole paper; a
                                    // selection → exactly its branches (E1).
                                    if (tickedIds.isEmpty()) {
                                        pendingExportPaperId = paper.id
                                    } else {
                                        pendingExportSelection =
                                            Triple(paper.id, tickedIds, tickedIds.size)
                                    }
                                    exportFormat = ExportFormat.JSON_INLINE
                                    showExportFormatDialog = true
                                },
                                onDelete = { viewModel.requestDelete(paper.id, paper.title) },
                                onAddCategory = {
                                    categoryDialogPaperId = paper.id
                                    categoryDialogParentId = null
                                    showCategoryDialog = true
                                },
                                onEditQuestion = { questionId, categoryId ->
                                    com.mcqapp.ui.editor.EditorSession.clear()
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.editorRoute(questionId, paper.id, categoryId)
                                    )
                                },
                                onAddQuestion = { categoryId ->
                                    com.mcqapp.ui.editor.EditorSession.clear()
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.editorRoute("", paper.id, categoryId)
                                    )
                                },
                                onMoveCategory = { categoryId, delta ->
                                    viewModel.moveCategory(categoryId, delta)
                                },
                                onNestCategory = { categoryId ->
                                    viewModel.nestCategory(categoryId)
                                },
                                onPromoteCategory = { categoryId ->
                                    viewModel.promoteCategory(categoryId)
                                },
                                onEditCategory = { node -> editingCategory = node },
                                onDeleteCategory = { categoryId ->
                                    viewModel.deleteCategory(categoryId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPaperDialog) {
        PaperDialog(
            onDismiss = { showPaperDialog = false },
            onSave = { title, description, duration, negative ->
                viewModel.addPaper(title, description, duration, negative)
                showPaperDialog = false
            }
        )
    }

    if (showCategoryDialog) {
        CategoryDialog(
            papers = papers,
            paperId = categoryDialogPaperId,
            parentId = categoryDialogParentId,
            onDismiss = { showCategoryDialog = false },
            onSave = { title, parent ->
                viewModel.addCategory(categoryDialogPaperId, title, parent)
                showCategoryDialog = false
            }
        )
    }

    editingCategory?.let { category ->
        EditCategoryDialog(
            category = category,
            papers = papers,
            onDismiss = { editingCategory = null },
            onSave = { title, parent ->
                viewModel.editCategory(category.id, title, parent)
                editingCategory = null
            }
        )
    }

    pendingDelete?.let { pending ->
        val impact = pending.impact
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Delete \u201c${pending.title}\u201d?") },
            text = {
                Column {
                    Text(
                        if (impact.questions == 1) "1 question" else "${impact.questions} questions",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Deletion also takes history, bookmarks and schedules, so
                    // say so rather than letting one tap destroy them.
                    if (impact.hasMoreThanQuestions) {
                        Spacer(Modifier.height(8.dp))
                        Text("This also deletes:", style = MaterialTheme.typography.bodySmall)
                        buildList {
                            if (impact.attempts > 0) add(
                                if (impact.attempts == 1) "1 past attempt"
                                else "${impact.attempts} past attempts"
                            )
                            if (impact.bookmarks > 0) add(
                                if (impact.bookmarks == 1) "1 bookmark"
                                else "${impact.bookmarks} bookmarks"
                            )
                            if (impact.schedules > 0) add(
                                if (impact.schedules == 1) "1 review schedule"
                                else "${impact.schedules} review schedules"
                            )
                        }.forEach {
                            Text("\u2022 $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelDelete()
                    viewModel.deletePaper(pending.paperId)
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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

    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissImportReport() },
            title = { Text(stringResource(R.string.imported)) },
            text = {
                Text(
                    buildString {
                        append(importReportTitle ?: "Deck")
                        append(": ")
                        append("${report.newPapers} new")
                        if (report.updatedPapers > 0) append(", ${report.updatedPapers} updated")
                        append(" paper${if (report.newPapers + report.updatedPapers == 1) "" else "s"}, ")
                        append("${report.newQuestions} new questions")
                        if (report.updatedQuestions > 0) append(", ${report.updatedQuestions} updated")
                        if (report.answersRefreshed > 0) {
                            append(", ${report.answersRefreshed} answers refreshed")
                        }
                        if (report.duplicateQuestions > 0) {
                            append(", ${report.duplicateQuestions} already present")
                        }
                        append('.')
                        if (report.restoredSchedules > 0) {
                            append(" Review progress kept for ${report.restoredSchedules} cards.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImportReport() }) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    if (showExportFormatDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportFormatDialog = false
                pendingExportPaperId = null
                pendingExportSelection = null
                pendingExportFormat = null
            },
            title = {
                Text(
                    if (pendingExportSelection != null) {
                        "Export selection (${pendingExportSelection!!.third} categories)"
                    } else {
                        "Export format"
                    }
                )
            },
            text = {
                Column {
                    ExportFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { exportFormat = format }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = exportFormat == format,
                                onClick = { exportFormat = format }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(format.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    format.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selection = pendingExportSelection
                    val paper = papers.firstOrNull { it.id == pendingExportPaperId }
                    val selectionPaper = papers.firstOrNull { it.id == selection?.first }
                    showExportFormatDialog = false
                    if (selection != null) {
                        pendingExportFormat = exportFormat
                        exportSaver(exportFormat).launch(
                            PaperExporter.fileNameFor(
                                "${selectionPaper?.title.orEmpty()} (selection)",
                                exportFormat
                            )
                        )
                    } else if (paper != null) {
                        pendingExportFormat = exportFormat
                        exportSaver(exportFormat)
                            .launch(PaperExporter.fileNameFor(paper.title, exportFormat))
                    } else {
                        pendingExportPaperId = null
                        pendingExportSelection = null
                        pendingExportFormat = null
                    }
                }) { Text(stringResource(R.string.export)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportFormatDialog = false
                    pendingExportPaperId = null
                    pendingExportSelection = null
                    pendingExportFormat = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
