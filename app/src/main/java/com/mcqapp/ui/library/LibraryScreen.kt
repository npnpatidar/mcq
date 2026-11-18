package com.mcqapp.ui.library

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
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
import com.mcqapp.domain.TestType
import com.mcqapp.ui.importscreen.ImportReportDetails
import com.mcqapp.ui.importscreen.displayTitle
import com.mcqapp.ui.common.LocalAnnouncer
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.common.UiMessageTone
import com.mcqapp.ui.library.TestSetupSheet
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
    val avoidRecentQuestions by viewModel.avoidRecentQuestions.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val exportError by viewModel.exportError.collectAsStateWithLifecycle()
    val sendMessage = LocalUiMessageSender.current
    val announcer = LocalAnnouncer.current
    // Export/delete failures already have a persistent error dialog; announce
    // the new message so screen readers hear it without a duplicate snackbar.
    LaunchedEffect(exportError) {
        val error = exportError
        if (error != null) announcer.announce(error)
    }
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
    // LocalResources (not LocalContext.current) keeps the chooser title
    // configuration-aware: LocalContext.getString() is not re-read when the
    // Configuration changes (LocalContextGetResourceValueCall).
    val resources = LocalResources.current
    val shareNoAppText = stringResource(R.string.share_no_app)

    // Dialog flags ride rotation (plain `remember` closed them and
    // discarded typed input) — consolidated_bugs.md F12.
    var showPaperDialog by rememberSaveable { mutableStateOf(false) }
    // The edit/pending-setup state is owned by the ViewModel (H12): plain
    // `remember` dropped an open dialog or a parked test setup on rotation,
    // losing typed input and — for a blueprint — turning an edit into a create.
    val editingPaper by viewModel.editingPaper.collectAsStateWithLifecycle()
    val pendingTestSetup by viewModel.pendingTestSetup.collectAsStateWithLifecycle()
    // Test-Start navigation is a VM-owned one-shot: a rotation between clearing
    // the sheet and the blueprint lookup used to cancel the composition scope
    // and silently drop the tap (L2). The screen only consumes the route.
    val testStartRoute by viewModel.testStartRoute.collectAsStateWithLifecycle()
    LaunchedEffect(testStartRoute) {
        val route = testStartRoute
        if (route != null) {
            viewModel.consumeTestStartRoute()
            navController.navigate(route)
        }
    }
    val isLoadingSample by viewModel.isLoadingSample.collectAsStateWithLifecycle()
    var showCategoryDialog by rememberSaveable { mutableStateOf(false) }
    var categoryDialogPaperId by rememberSaveable { mutableStateOf("") }
    var categoryDialogParentId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingCategory by viewModel.editingCategory.collectAsStateWithLifecycle()
    // The export target (paper or selection, and the chosen format) lives in
    // the ViewModel: the system save dialog recreates the activity, and plain
    // `remember` came back all-null, so the re-delivered URI fell through to
    // exportAll — the whole library written to one paper's file
    // (consolidated_bugs.md F2).
    val pendingExportTarget by viewModel.pendingExport.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    var showExportFormatDialog by rememberSaveable { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(ExportFormat.JSON_INLINE) }

    // The format must travel in state: saver callbacks fire after the save
    // dialog, so anything baked into the callback itself goes stale.
    fun onExportDocument(uri: Uri?) {
        val pending = viewModel.consumePendingExport()
        uri?.let {
            val selection = pending?.selection
            val format = pending?.format
            when {
                selection != null && format != null ->
                    viewModel.exportSelectionAs(it, selection.first, selection.second, format)
                pending?.paperId != null && format != null ->
                    viewModel.exportPaperAs(it, pending.paperId, format)
                // Nothing pending: the dialog was dismissed or the state was
                // lost. exportAll has no legitimate caller here, so doing
                // nothing is the only safe answer.
                else -> Unit
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
        // The classify/read pipeline runs in the ViewModel: the composition
        // scope this callback used to run in is cancelled by the recreation a
        // picker can itself trigger, silently dropping the pick with no error
        // and no navigation (consolidated_bugs.md F13).
        viewModel.onImportPicked(
            picked,
            context.contentResolver,
            onDocx = { bytes, name ->
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxBytes = bytes
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingDocxName = name
                navController.navigate("import/direct")
            },
            onJsonUri = {
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonUri = it
                navController.navigate("import/direct")
            },
            onJsonBytes = {
                com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonBytes = it
                navController.navigate("import/direct")
            }
        )
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
                    onOpenSessions = {
                        scope.launch { drawerState.close() }
                        navController.navigate("sessions")
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
                        val paper = papers.firstOrNull { it.id == paperId }
                        Logger.i("LIB", "Drawer test: paperId=$paperId, categories=${categoryIds.size}")
                        if (paper != null) {
                            viewModel.setPendingTestSetup(
                                LibraryViewModel.PendingTestSetup(paper, categoryIds, com.mcqapp.domain.TestType.RANDOM)
                            )
                        } else {
                            // No paper record (should not happen): fall back to
                            // the paper's own duration rather than dropping the tap.
                            navController.navigate(
                                com.mcqapp.ui.navigation.testRoute(paperId, categoryIds)
                            )
                        }
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
                            TextButton(
                                onClick = { viewModel.loadSampleData() },
                                enabled = !isLoadingSample
                            ) {
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
                        itemsIndexed(papers, key = { _, paper -> paper.id }) { index, paper ->
                            val dueCountBadge = studyCounts[paper.id]?.due ?: 0
                            val leechCountBadge = studyCounts[paper.id]?.leeches ?: 0
                            PaperCard(
                                canMoveUp = index > 0,
                                canMoveDown = index < papers.lastIndex,
                                onMovePaper = { delta -> viewModel.movePaper(paper.id, delta) },
                                paper = paper,
                                onStart = {
                                    viewModel.setPendingTestSetup(
                                        LibraryViewModel.PendingTestSetup(
                                            paper, emptyList(), com.mcqapp.domain.TestType.RANDOM
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
                                onBrowse = { ids ->
                                    // Browse is a pre-filter, not a hidden scope
                                    // (§15.2): the route seeds the screen's
                                    // category filter, which the user can clear.
                                    Logger.i("LIB", "Browse: paperId=${paper.id}, categories=${ids.size}")
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.browseRoute(paper.id, categoryIds = ids)
                                    )
                                },
                                onScopedTest = { ids ->
                                    Logger.i("LIB", "Card test: paperId=${paper.id}, categories=${ids.size}")
                                    viewModel.setPendingTestSetup(
                                        LibraryViewModel.PendingTestSetup(
                                            paper, ids, com.mcqapp.domain.TestType.RANDOM
                                        )
                                    )
                                },
                                onPracticeMistakes = { ids ->
                                    Logger.i("LIB", "Card mistakes: paperId=${paper.id}, categories=${ids.size}")
                                    viewModel.setPendingTestSetup(
                                        LibraryViewModel.PendingTestSetup(
                                            paper, ids, com.mcqapp.domain.TestType.MISTAKES
                                        )
                                    )
                                },
                                onExport = { tickedIds ->
                                    // Nothing ticked → the whole paper; a
                                    // selection → exactly its branches (E1).
                                    viewModel.setPendingExport(
                                        if (tickedIds.isEmpty()) {
                                            LibraryViewModel.PendingExportTarget(paperId = paper.id)
                                        } else {
                                            LibraryViewModel.PendingExportTarget(
                                                selection = Triple(paper.id, tickedIds, tickedIds.size)
                                            )
                                        }
                                    )
                                    exportFormat = ExportFormat.JSON_INLINE
                                    showExportFormatDialog = true
                                },
                                onDelete = { viewModel.requestDelete(paper.id, paper.title) },
                                onAddCategory = {
                                    categoryDialogPaperId = paper.id
                                    categoryDialogParentId = null
                                    showCategoryDialog = true
                                },
                                onEditPaper = { viewModel.beginEditPaper(paper) },
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
                                onEditCategory = { node -> viewModel.beginEditCategory(node) },
                                // Tapping a category name opens it in Browse,
                                // scoped to that branch: editing moved into the
                                // row's ⋮ menu, browsing is the common tap.
                                onOpenCategory = { node ->
                                    navController.navigate(
                                        com.mcqapp.ui.navigation.browseRoute(
                                            paper.id,
                                            categoryIds = listOf(node.id)
                                        )
                                    )
                                },
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

    editingPaper?.let { paper ->
        PaperDialog(
            paper = paper,
            onDismiss = { viewModel.clearEditPaper() },
            onSave = { title, description, duration, negative ->
                viewModel.updatePaper(paper.id, title, description, duration, negative)
                viewModel.clearEditPaper()
            }
        )
    }

    pendingTestSetup?.let { request ->
        TestSetupSheet(
            paper = request.paper,
            initialType = request.type,
            mistakeCount = mistakeCounts[request.paper.id] ?: 0,
            categoryIds = request.categoryIds,
            avoidRecentQuestions = avoidRecentQuestions,
            loadScopedMistakeCount = { scopeIds ->
                viewModel.repository.getScopedMistakeCount(request.paper.id, scopeIds)
            },
            loadMinBlockSize = { type, scopeIds ->
                when (type) {
                    TestType.MISTAKES ->
                        viewModel.repository.getMistakenMinBlockSize(request.paper.id, scopeIds)
                    else -> viewModel.repository.getMinBlockSize(request.paper.id, scopeIds)
                }
            },
            loadPreview = { type, count, breadth, variant, exclusion ->
                viewModel.previewSelection(
                    request.paper,
                    request.categoryIds,
                    type,
                    count,
                    breadth,
                    variant,
                    exclusion
                )
            },
            loadLastLength = { viewModel.lastTestLength() },
            loadTags = {
                viewModel.tagsForScope(request.paper.id, request.categoryIds)
            },
            loadPanels = {
                viewModel.panelsForPaper(request.paper.id)
            },
            onCreatePanel = { name, questionIds ->
                viewModel.createPanel(request.paper.id, name, questionIds)
            },
            onDeletePanel = { panelId -> viewModel.deletePanel(panelId) },
            loadBlueprints = { paperId -> viewModel.blueprintsForPaper(paperId) },
            loadBlueprintSections = { blueprintId -> viewModel.blueprintSections(blueprintId) },
            onCreateBlueprint = { name, sections ->
                viewModel.createBlueprint(request.paper.id, name, sections)
            },
            onUpdateBlueprint = { blueprintId, name, sections ->
                viewModel.updateBlueprint(blueprintId, name, sections)
            },
            onDeleteBlueprint = { blueprintId -> viewModel.deleteBlueprint(blueprintId) },
            onDismiss = { viewModel.setPendingTestSetup(null) },
            onStart = { type, requestedCount, minutes, exclusion, breadth, variant ->
                // The ViewModel clears the sheet, remembers the length, resolves
                // a blueprint's section union and emits the route. Keeping this
                // out of rememberCoroutineScope means a rotation can no longer
                // cancel the lookup after the sheet is gone (L2).
                viewModel.startTest(request, type, requestedCount, minutes, exclusion, breadth, variant)
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
            onDismiss = { viewModel.clearEditCategory() },
            onSave = { title, parent ->
                viewModel.editCategory(category.id, title, parent)
                viewModel.clearEditCategory()
            }
        )
    }

    pendingDelete?.let { pending ->
        val impact = pending.impact
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text(stringResource(R.string.delete_paper_confirm_title, displayTitle(pending.title))) },
            text = {
                Column {
                    Text(
                        pluralStringResource(R.plurals.sessions_questions, impact.questions, impact.questions),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Deletion also takes history, bookmarks and schedules, so
                    // say so rather than letting one tap destroy them.
                    if (impact.hasMoreThanQuestions) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.delete_paper_also_deletes), style = MaterialTheme.typography.bodySmall)
                        buildList {
                            if (impact.attempts > 0) add(
                                pluralStringResource(R.plurals.past_attempts_count, impact.attempts, impact.attempts)
                            )
                            if (impact.bookmarks > 0) add(
                                pluralStringResource(R.plurals.bookmarks_count, impact.bookmarks, impact.bookmarks)
                            )
                            if (impact.schedules > 0) add(
                                pluralStringResource(R.plurals.review_schedules_count, impact.schedules, impact.schedules)
                            )
                        }.forEach {
                            Text(stringResource(R.string.bullet_item, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.this_cannot_be_undone),
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
                Column {
                    Text(
                        importReportTitle?.let { displayTitle(it) }
                            ?: stringResource(R.string.final_fallback_deck_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    // The same tested, plural-aware enumeration the JSON import
                    // dialog uses: it now names restored schedules, papers and
                    // the Anki drops/conversions that never reached the library.
                    ImportReportDetails(report)
                    // This dialog is opened only by importAnkiPackage, so an
                    // Anki deck's scheduling memory is always in play here. The
                    // note is informational and never blocks the dialog.
                    Spacer(Modifier.height(8.dp))
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
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImportReport() }) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    if (showExportFormatDialog) {
        fun closeExportDialog() {
            showExportFormatDialog = false
            viewModel.setPendingExport(null)
        }

        // Two direct actions: the format rows choose the format, and these
        // buttons choose what to do with it. There is no separate mode chip to
        // set first, so Save or Share is one tap.
        fun saveExportToFile() {
            val target = pendingExportTarget
            if (target == null || (target.paperId == null && target.selection == null)) {
                closeExportDialog()
                return
            }
            val paper = papers.firstOrNull { it.id == target.paperId }
            val selectionPaper = papers.firstOrNull { it.id == target.selection?.first }
            showExportFormatDialog = false
            viewModel.setPendingExport(target.copy(format = exportFormat))
            exportSaver(exportFormat).launch(
                if (target.selection != null) {
                    PaperExporter.fileNameFor(
                        "${selectionPaper?.title.orEmpty()} (selection)",
                        exportFormat
                    )
                } else {
                    PaperExporter.fileNameFor(paper?.title.orEmpty(), exportFormat)
                }
            )
        }

        fun shareExport() {
            val target = pendingExportTarget ?: run {
                closeExportDialog()
                return
            }
            scope.launch {
                val intent = if (target.selection != null) {
                    viewModel.shareSelectionIntent(
                        target.selection.first,
                        target.selection.second,
                        exportFormat
                    )
                } else if (target.paperId != null) {
                    viewModel.sharePaperIntent(target.paperId, exportFormat)
                } else {
                    null
                }
                closeExportDialog()
                if (intent != null) {
                    try {
                        context.startActivity(
                            Intent.createChooser(
                                intent,
                                resources.getString(R.string.share_chooser_title)
                            )
                        )
                    } catch (_: ActivityNotFoundException) {
                        sendMessage(UiMessage(shareNoAppText, UiMessageTone.Error))
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { closeExportDialog() },
            title = {
                // The formerly hardcoded "Export selection (N categories)" and
                // "Export format" titles now come from resources in both locales.
                val selection = pendingExportTarget?.selection
                Text(
                    if (selection != null) {
                        pluralStringResource(
                            R.plurals.r2_lib_export_selection_title, selection.third, selection.third
                        )
                    } else {
                        stringResource(R.string.r2_lib_export_format_title)
                    }
                )
            },
            text = {
                Column {
                    if (isSharing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sharing))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
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
                                Text(
                                    stringResource(format.titleRes),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                // stringResource is @Composable, so resolve both pieces in
                                // composition and hand the helper already-localized values.
                                val baseDescription = stringResource(format.descriptionRes)
                                val caveat = if (format.contentOnlyNoteRes != 0) {
                                    stringResource(format.contentOnlyNoteRes)
                                } else ""
                                val descriptionWithNote = format.descriptionWithCaveat { id ->
                                    if (id == format.contentOnlyNoteRes) caveat else baseDescription
                                }
                                Text(
                                    descriptionWithNote,
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
                    TextButton(
                        enabled = !isSharing,
                        onClick = { saveExportToFile() }
                    ) {
                        Text(stringResource(R.string.share_save))
                    }
                    TextButton(
                        enabled = !isSharing,
                        onClick = { shareExport() }
                    ) {
                        Text(stringResource(R.string.share_share))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isSharing, onClick = { closeExportDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
