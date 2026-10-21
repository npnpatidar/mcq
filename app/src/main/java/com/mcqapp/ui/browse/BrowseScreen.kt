package com.mcqapp.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.mcqapp.data.repository.BrowseListFilter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Question
import com.mcqapp.ui.BrowseViewModelFactory
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    repository: McqRepository,
    paperId: String,
    focusQuestionId: String = "",
    navController: NavController,
    viewModel: BrowseViewModel = viewModel(
        key = "browse-$paperId",
        factory = BrowseViewModelFactory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
            paperId
        )
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allPapers by viewModel.papers.collectAsStateWithLifecycle()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    // The id set is captured at pick time: CreateDocument hands back a Uri
    // later, by which point selection mode may already be over.
    var exportIds by remember { mutableStateOf(emptySet<String>()) }
    var exportFormat by remember { mutableStateOf(com.mcqapp.data.export.ExportFormat.JSON_INLINE) }

    val exportError by viewModel.exportError.collectAsStateWithLifecycle()

    fun exportTitle(): String {
        val paper = state.paper?.title ?: "Selected"
        return if (exportIds.size == 1) "$paper (1 question)"
        else "$paper (${exportIds.size} questions)"
    }

    fun onExportDocument(uri: android.net.Uri?) {
        val format = exportFormat
        val ids = exportIds
        if (uri != null && ids.isNotEmpty()) viewModel.exportSelection(uri, ids, exportTitle(), format)
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.mcqapp.data.export.ExportFormat.JSON_INLINE.mimeType)
    ) { uri: android.net.Uri? -> onExportDocument(uri) }
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.mcqapp.data.export.ExportFormat.ZIP.mimeType)
    ) { uri: android.net.Uri? -> onExportDocument(uri) }
    val exportHtmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.mcqapp.data.export.ExportFormat.HTML.mimeType)
    ) { uri: android.net.Uri? -> onExportDocument(uri) }
    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.mcqapp.data.export.ExportFormat.PDF.mimeType)
    ) { uri: android.net.Uri? -> onExportDocument(uri) }
    val exportApkgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.mcqapp.data.export.ExportFormat.APKG.mimeType)
    ) { uri: android.net.Uri? -> onExportDocument(uri) }
    val exportDocxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.mcqapp.data.export.ExportFormat.DOCX.mimeType)
    ) { uri: android.net.Uri? -> onExportDocument(uri) }

    fun exportSaver(format: com.mcqapp.data.export.ExportFormat) = when (format) {
        com.mcqapp.data.export.ExportFormat.JSON_INLINE -> exportJsonLauncher
        com.mcqapp.data.export.ExportFormat.ZIP -> exportZipLauncher
        com.mcqapp.data.export.ExportFormat.HTML, com.mcqapp.data.export.ExportFormat.HTML_QUIZ ->
            exportHtmlLauncher
        com.mcqapp.data.export.ExportFormat.PDF, com.mcqapp.data.export.ExportFormat.PDF_ANSWER_KEY ->
            exportPdfLauncher
        com.mcqapp.data.export.ExportFormat.APKG -> exportApkgLauncher
        com.mcqapp.data.export.ExportFormat.DOCX -> exportDocxLauncher
    }
    var showBulkDialog by remember { mutableStateOf(false) }
    var bulkMarks by remember { mutableStateOf("") }
    var bulkTags by remember { mutableStateOf("") }
    var bulkDifficulty by remember { mutableStateOf<com.mcqapp.domain.Difficulty?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "${selectedIds.size} selected"
                        else state.paper?.title ?: "Browse questions"
                    )
                },
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
                            if (selectionMode) Icons.Default.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                bulkMarks = ""
                                bulkTags = ""
                                bulkDifficulty = null
                                showBulkDialog = true
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) { Text(stringResource(R.string.edit)) }
                        TextButton(
                            onClick = { showMoveDialog = true },
                            enabled = selectedIds.isNotEmpty()
                        ) { Text(stringResource(R.string.move)) }
                        TextButton(
                            onClick = {
                                exportFormat = com.mcqapp.data.export.ExportFormat.JSON_INLINE
                                exportIds = selectedIds
                                showExportDialog = true
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) { Text(stringResource(R.string.export)) }
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) { Text(stringResource(R.string.delete)) }
                    } else {
                        TextButton(onClick = { selectionMode = true }) { Text(stringResource(R.string.select)) }
                    }
                }
            )
        }
    ) { padding ->
        val items = viewModel.pagingData.collectAsLazyPagingItems()
        val filterState by viewModel.filterState.collectAsStateWithLifecycle()

        // The field shows what was typed; the list's query follows 300 ms later.
        var searchInput by remember { mutableStateOf("") }
        LaunchedEffect(searchInput) {
            if (searchInput != filterState.query) {
                delay(300)
                viewModel.updateQuery(searchInput)
            }
        }
        LaunchedEffect(filterState.query) {
            if (filterState.query != searchInput) searchInput = filterState.query
        }

        val flatCategories = remember(state.paper) {
            CategorySelection.flatten(state.paper?.categories ?: emptyList())
        }
        // The category each row belongs to (label on the card).
        val categoryTitles = remember(flatCategories) {
            flatCategories.associate { it.first.id to it.first.title }
        }
        // The drawer's reorder, answered inline: whether a category can move
        // among its siblings (same parent) — the section-edge buttons use it.
        val categoryMove = remember(state.paper, flatCategories) {
            fun canMove(categoryId: String, up: Boolean): Boolean {
                val self = flatCategories.firstOrNull { it.first.id == categoryId } ?: return false
                val siblings = flatCategories.filter { it.first.parentId == self.first.parentId }
                val index = siblings.indexOfFirst { it.first.id == categoryId }
                if (index < 0) return false
                return if (up) index > 0 else index < siblings.size - 1
            }
            ::canMove
        }
        var categoryMenuOpen by remember { mutableStateOf(false) }
        val categoryFilterId = filterState.categoryFilterId
        val selectedCategoryTitle = categoryFilterId
            ?.let { id -> flatCategories.firstOrNull { it.first.id == id }?.first?.title }

        // Delayed spinner: fast loads show blank instead of flashing text.
        val refreshing = items.loadState.refresh is LoadState.Loading
        val showLoading = com.mcqapp.util.rememberDelayedVisibility(refreshing && items.itemCount == 0)
        if (showLoading) {
            Text(
                stringResource(R.string.loading),
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        // Section (whole-category) moves need the rendered list to *be* the
        // paper-wide list: any filter hides rows, so those buttons only show
        // unfiltered (the pre-category-filter contract). Question- and
        // passage-block moves are computed against the question's own
        // category list, which no filter reorders.
        val categorySectionMoveEnabled = filterState.filter == BrowseListFilter.ALL &&
            filterState.query.isBlank() && categoryFilterId == null
        val questionMoveEnabled = filterState.filter == BrowseListFilter.ALL &&
            filterState.query.isBlank()

        val listState = rememberLazyListState()
        // Deep link from global search: highlight persists on screen.
        val highlightId = focusQuestionId
        // The list is a paging window, so on the first composition it has no
        // items at all, and a question far down a long paper lies past
        // everything the window holds. A scroll asked for then went to a list
        // that did not exist yet and was never asked again, which is why a
        // search hit landed at the top. Read the count here, in composition, so
        // the effect runs again once the list has one — paging then jumps to
        // the row and loads the page around it.
        val listHasRows = items.itemCount > 0
        LaunchedEffect(focusQuestionId, listHasRows) {
            if (focusQuestionId.isBlank() || !listHasRows) return@LaunchedEffect
            viewModel.browseIndexOf(focusQuestionId) { index ->
                // +1 for the header item above the cards.
                if (index >= 0) listState.scrollToItem(index + 1)
            }
        }
        // Keep the rows around the viewport loaded, cards and passages
        // included. A row that is already complete when it scrolls in cannot
        // grow under the finger, and a passage header is known before its row
        // is laid out instead of being inserted afterwards and pushing the
        // rows below it down. snapshotFlow keeps the window read out of
        // composition, and reading it through peek() makes the flow re-run
        // when a placeholder's page lands, not only when the scroll moves.
        LaunchedEffect(items, listState, state.contextsVersion) {
            snapshotFlow {
                val info = listState.layoutInfo
                browseWindow(
                    peek = { index -> items.peek(index) },
                    itemCount = items.itemCount,
                    firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1,
                    lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                )
            }
                .distinctUntilChanged()
                .collect { rows -> if (rows.isNotEmpty()) viewModel.onVisibleRows(rows) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "browse-controls") {
                androidx.compose.material3.OutlinedTextField(
                    // The field shows what was typed; the list follows 300 ms
                    // later, so the filter does not run on every keystroke.
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    label = { Text(stringResource(R.string.search_text_tags_options)) },
                    singleLine = true,
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            IconButton(onClick = { searchInput = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "All" to BrowseListFilter.ALL,
                        "No answer" to BrowseListFilter.NO_ANSWER,
                        "No explanation" to BrowseListFilter.NO_EXPLANATION,
                        "Uncategorized" to BrowseListFilter.UNCATEGORIZED
                    ).forEach { (label, kind) ->
                        FilterChip(
                            selected = filterState.filter == kind,
                            onClick = { viewModel.updateFilter(kind) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // One category at a time: a paper with dozens of categories
                // otherwise needs endless scrolling to reach a single one.
                ExposedDropdownMenuBox(
                    expanded = categoryMenuOpen,
                    onExpandedChange = { categoryMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = selectedCategoryTitle
                            ?: stringResource(R.string.all_categories),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.category)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.all_categories)) },
                            onClick = {
                                viewModel.updateCategoryFilter(null)
                                categoryMenuOpen = false
                            }
                        )
                        flatCategories.forEach { (node, depth) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.label).repeat(depth) + node.title,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                onClick = {
                                    viewModel.updateCategoryFilter(node.id)
                                    categoryMenuOpen = false
                                }
                            )
                        }
                    }
                }
                if (!refreshing && items.itemCount == 0) {
                    val unfiltered = filterState.filter == BrowseListFilter.ALL &&
                        filterState.query.isBlank() && categoryFilterId == null
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            if (unfiltered) R.string.no_questions_in_this_paper
                            else R.string.no_questions_match_this_filter
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            items(
                count = items.itemCount,
                key = items.itemKey { it.id },
                contentType = { "question" }
            ) { index ->
                val question = items[index]
                if (question == null) {
                    // A row whose page has not been loaded yet. The list knows
                    // the paper's whole length (placeholders are on), so the
                    // number is already the right one; the block stands in —
                    // card-shaped, so that the page landing does not resize the
                    // row under the scroll — until the page arrives, so a
                    // deep-link jump or a fast fling never leaves a blank gap
                    // where questions are.
                    BrowseRowSkeleton(index = index + 1)
                    return@items
                }
                val context = state.categoryContexts[question.categoryId]
                val categoryList = context?.refs.orEmpty()
                // Index and enable state are precomputed once per window; the
                // tap handlers below still compute the new order on demand.
                val moves = state.rowMoves[question.id]
                // A section's first/last rows carry the category moves (their
                // own question is pinned at the edge anyway), matched by id
                // from the category's context rather than a rendered index.
                val isCategoryFirst = categorySectionMoveEnabled && context != null &&
                    context.firstId == question.id
                val isCategoryLast = categorySectionMoveEnabled && context != null &&
                    context.lastId == question.id
                val moveOrder: (Boolean) -> Unit = { up ->
                    com.mcqapp.domain.PassageReorder
                        .moveQuestion(categoryList, question.id, up, { it.id }, { it.passageId })
                        ?.let { viewModel.renumberCategory(question.categoryId, it) }
                }
                // Header inline above the first member of each passage group;
                // later members carry a small label so the membership is
                // visible mid-group too.
                val passageId = question.passageId
                val isPassageFirst = passageId != null &&
                    state.passageFirstIds[passageId] == question.id
                val passage = passageId?.let { state.passages[it] }
                if (isPassageFirst && passage != null) {
                    PassageHeaderCard(
                        passage = passage,
                        canMoveUp = questionMoveEnabled && moves?.passageCanMoveUp == true,
                        canMoveDown = questionMoveEnabled && moves?.passageCanMoveDown == true,
                        onMoveUp = {
                            com.mcqapp.domain.PassageReorder
                                .movePassageBlock(categoryList, passageId, up = true, { it.id }, { it.passageId })
                                ?.let { viewModel.renumberCategory(question.categoryId, it) }
                        },
                        onMoveDown = {
                            com.mcqapp.domain.PassageReorder
                                .movePassageBlock(categoryList, passageId, up = false, { it.id }, { it.passageId })
                                ?.let { viewModel.renumberCategory(question.categoryId, it) }
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (passageId != null && passage != null) {
                    Text(
                        stringResource(
                            R.string.in_passage_label,
                            passage.title.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.passage)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(2.dp))
                }
                // The row's full card, read with the viewport window (ahead of
                // the row becoming visible) rather than by the row itself: a
                // card fetched when its row composes grows after it has been
                // laid out, which is what the scroll felt like while it settled.
                val rowContent = state.contentById[question.id]
                rowContent?.let { content ->
                    BrowseQuestionCard(
                        index = index + 1,
                        question = content,
                        categoryTitle = categoryTitles[question.categoryId],
                        selectionMode = selectionMode,
                        selected = question.id in selectedIds,
                        highlighted = question.id == highlightId,
                        moveUpIsCategory = isCategoryFirst,
                        moveDownIsCategory = isCategoryLast,
                        canMoveUp = if (isCategoryFirst) {
                            categoryMove(question.categoryId, true)
                        } else {
                            questionMoveEnabled && moves?.canMoveUp == true
                        },
                        canMoveDown = if (isCategoryLast) {
                            categoryMove(question.categoryId, false)
                        } else {
                            questionMoveEnabled && moves?.canMoveDown == true
                        },
                        onMoveUp = {
                            if (isCategoryFirst) viewModel.moveCategory(question.categoryId, -1)
                            else moveOrder(true)
                        },
                        onMoveDown = {
                            if (isCategoryLast) viewModel.moveCategory(question.categoryId, +1)
                            else moveOrder(false)
                        },
                        onToggleSelect = {
                            selectedIds = selectedIds.toMutableSet().apply {
                                if (!add(question.id)) remove(question.id)
                            }
                        },
                        onEdit = {
                            viewModel.openEditor(question.id) { cursor ->
                                com.mcqapp.ui.editor.EditorSession.startCursor(cursor)
                                navController.navigate(
                                    com.mcqapp.ui.navigation.editorRoute(question.id, paperId, question.categoryId)
                                )
                            }
                        },
                        onDelete = { viewModel.deleteQuestion(question.id) },
                        onDuplicate = { viewModel.duplicateQuestion(question.id) }
                    )
                }
                if (rowContent == null) {
                    // The ref has landed but its card has not. The seat used to
                    // hold the stem text: that reads as a card that lost its
                    // options, and a three-line stem is not the card's shape, so
                    // the row resized the moment the card arrived. A card-shaped
                    // block keeps the height instead, and the request below
                    // covers the one case the window cannot — a landing between
                    // two equal windows, where nothing new is emitted and the row
                    // would otherwise wait for a read that never comes.
                    BrowseRowSkeleton(index = index + 1)
                    LaunchedEffect(question.id) { viewModel.requestRow(question) }
                }
            }
        }

        if (showBulkDialog) {
            AlertDialog(
                onDismissRequest = { showBulkDialog = false },
                title = { Text("Edit ${selectedIds.size} questions") },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.blank_fields_keep_existing_values),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = bulkMarks,
                            onValueChange = { bulkMarks = it },
                            label = { Text(stringResource(R.string.marks_blank_keep)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.difficulty), style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = bulkDifficulty == null,
                                onClick = { bulkDifficulty = null },
                                label = { Text(stringResource(R.string.keep)) }
                            )
                            com.mcqapp.domain.Difficulty.entries.forEach { d ->
                                FilterChip(
                                    selected = bulkDifficulty == d,
                                    onClick = { bulkDifficulty = d },
                                    label = { Text(d.label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = bulkTags,
                            onValueChange = { bulkTags = it },
                            label = { Text(stringResource(R.string.tags_comma_separated_blank_keep)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.bulkEdit(selectedIds, bulkMarks, bulkDifficulty, bulkTags)
                        selectedIds = emptySet()
                        selectionMode = false
                        showBulkDialog = false
                    }) { Text(stringResource(R.string.apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete ${selectedIds.size} questions?") },
                text = { Text(stringResource(R.string.this_cannot_be_undone)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteQuestions(selectedIds)
                        selectedIds = emptySet()
                        selectionMode = false
                        showDeleteConfirm = false
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        exportError?.let { message ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissExportError() },
                title = { Text(stringResource(R.string.export)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissExportError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }

        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export ${exportIds.size} selected questions") },
                text = {
                    Column {
                        com.mcqapp.data.export.ExportFormat.entries.forEach { format ->
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
                        showExportDialog = false
                        exportSaver(exportFormat).launch(
                            com.mcqapp.data.export.PaperExporter.fileNameFor(
                                exportTitle(), exportFormat
                            )
                        )
                        // Selection mode ends with the export: the ids are now
                        // held by exportIds, and the user asked for a file.
                        selectionMode = false
                        selectedIds = emptySet()
                    }) { Text(stringResource(R.string.export)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showMoveDialog) {
            // Local to the dialog: forgotten on dismiss, so every open starts fresh.
            var moveIsCopy by remember { mutableStateOf(false) }
            var movePaperId by remember { mutableStateOf(paperId) }
            var paperMenuOpen by remember { mutableStateOf(false) }
            val movePaper = remember(allPapers, movePaperId) {
                allPapers.find { it.id == movePaperId }
            }
            val targets = remember(movePaper) {
                CategorySelection.flatten(movePaper?.categories ?: emptyList())
            }
            AlertDialog(
                onDismissRequest = { showMoveDialog = false },
                title = {
                    Text("${if (moveIsCopy) "Copy" else "Move"} ${selectedIds.size} questions to…")
                },
                text = {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !moveIsCopy,
                                onClick = { moveIsCopy = false },
                                label = { Text(stringResource(R.string.move)) }
                            )
                            FilterChip(
                                selected = moveIsCopy,
                                onClick = { moveIsCopy = true },
                                label = { Text(stringResource(R.string.copy)) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = paperMenuOpen,
                            onExpandedChange = { paperMenuOpen = it }
                        ) {
                            OutlinedTextField(
                                value = movePaper?.title ?: "Select paper",
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                label = { Text(stringResource(R.string.paper)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = paperMenuOpen)
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = paperMenuOpen,
                                onDismissRequest = { paperMenuOpen = false }
                            ) {
                                allPapers.forEach { paper ->
                                    DropdownMenuItem(
                                        text = { Text(paper.title) },
                                        onClick = {
                                            movePaperId = paper.id
                                            paperMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        targets.forEach { (node, depth) ->
                            TextButton(
                                onClick = {
                                    if (moveIsCopy) {
                                        viewModel.copyQuestions(selectedIds, node.id)
                                    } else {
                                        viewModel.moveQuestions(selectedIds, node.id)
                                    }
                                    selectedIds = emptySet()
                                    selectionMode = false
                                    showMoveDialog = false
                                }
                            ) {
                                Text(
                                    "${"— ".repeat(depth)}${node.title} (${node.questionCount})",
                                    maxLines = 1
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showMoveDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}

/**
 * A card-shaped stand-in for a row whose page has not landed. The number is
 * already the right one (placeholders are on, so the list knows the paper's
 * whole length); the block is the shape of the card it becomes, so a page
 * arriving does not resize the row under the scroll — a 14 dp bar growing into
 * a 300 dp card is what settling felt like.
 */
@Composable
private fun BrowseRowSkeleton(index: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$index.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                SkeletonBar(Modifier.weight(0.8f).height(14.dp))
            }
            Spacer(Modifier.height(10.dp))
            repeat(4) { line ->
                SkeletonBar(
                    Modifier
                        .fillMaxWidth(if (line % 2 == 0) 0.9f else 0.7f)
                        .height(12.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier) {
    Spacer(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
private fun BrowseQuestionCard(
    index: Int,
    question: com.mcqapp.domain.Question,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    /** Section edges: the button moves the whole category, not the question. */
    moveUpIsCategory: Boolean = false,
    moveDownIsCategory: Boolean = false,
    /** The row's category, labelled so a section's bounds (and its pinned
     *  edge buttons) explain themselves. */
    categoryTitle: String? = null,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {},
    highlighted: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (categoryTitle != null) {
                Text(
                    categoryTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                } else {
                    Text(
                        "$index.",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                ContentElements(
                    question.elements,
                    textStyle = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
            }
            QuestionImage(
                src = question.image,
                contentDescription = stringResource(R.string.question_image),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(8.dp))
            question.options.forEach { option ->
                val isCorrect = option.id in question.correctOptionIds
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        if (isCorrect) "✓" else "○",
                        color = if (isCorrect) verdictColors().correctBorder else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(20.dp),
                        fontWeight = if (isCorrect) FontWeight.Bold else FontWeight.Normal
                    )
                    ContentElements(option.elements, textStyle = MaterialTheme.typography.bodyMedium)
                }
                if (option.image != null) {
                    QuestionImage(
                        src = option.image,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp)
                    )
                }
            }

            if (question.explanationElements.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = stringResource(R.string.explanation),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    ContentElements(
                        question.explanationElements,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            QuestionImage(src = question.explanationImage, contentDescription = stringResource(R.string.explanation_image))

            Spacer(Modifier.height(8.dp))
            if (!selectionMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canMoveUp || canMoveDown) {
                        // Double arrows mark a section move: the button
                        // carries the whole category past its sibling, like
                        // the drawer's category reorder.
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(
                                if (moveUpIsCategory) Icons.Default.KeyboardDoubleArrowUp
                                else Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(
                                    if (moveUpIsCategory) R.string.move_category_up
                                    else R.string.move_up
                                )
                            )
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(
                                if (moveDownIsCategory) Icons.Default.KeyboardDoubleArrowDown
                                else Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(
                                    if (moveDownIsCategory) R.string.move_category_down
                                    else R.string.move_down
                                )
                            )
                        }
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_question))
                    }
                    IconButton(onClick = onDuplicate) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.duplicate_question))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_question))
                    }
                }
            }
        }
    }
}

/** The shared-context card above a passage group in the browse list. */
@Composable
private fun PassageHeaderCard(
    passage: com.mcqapp.domain.Passage,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    passage.title.ifBlank { stringResource(R.string.passage) },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                // The whole group — passage plus its questions — moves as a
                // block; a member's own up/down stay inside the group.
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_passage_up))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_passage_down))
                }
            }
            Spacer(Modifier.height(4.dp))
            ContentElements(passage.elements, textStyle = MaterialTheme.typography.bodyMedium)
            QuestionImage(
                src = passage.image,
                contentDescription = stringResource(R.string.passage_image),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * The browse screen's single-category filter: with a set of category ids, only
 * those categories' questions remain; null (or empty) keeps the list whole.
 */
internal fun applyCategoryFilter(
    questions: List<com.mcqapp.domain.Question>,
    categoryIds: Set<String>?
): List<com.mcqapp.domain.Question> =
    applyCategoryFilter(questions, categoryIds) { it.categoryId }

/**
 * The browse screen's single-category filter over any item that knows its
 * category: with a set of category ids, only those categories' items remain;
 * null (or empty) keeps the list whole.
 */
internal fun <T> applyCategoryFilter(
    questions: List<T>,
    categoryIds: Set<String>?,
    categoryIdOf: (T) -> String
): List<T> =
    if (categoryIds.isNullOrEmpty()) questions
    else questions.filter { categoryIdOf(it) in categoryIds }

/**
 * The ids of [rootId] plus every descendant, read from the pre-order
 * [flatCategories] list: a node's subtree is exactly the nodes that follow it
 * until the next node of depth <= its own. A null [rootId] means "no filter"
 * (null); a non-null but unknown id filters to nothing (empty set) rather than
 * showing everything.
 *
 * The contract and the scan live in [CategorySelection.subtreeIds]; this keeps
 * the name its callers and tests already use.
 */
internal fun subtreeIdsOf(
    flatCategories: List<Pair<com.mcqapp.domain.CategoryNode, Int>>,
    rootId: String?
): Set<String>? = CategorySelection.subtreeIds(flatCategories, rootId)
