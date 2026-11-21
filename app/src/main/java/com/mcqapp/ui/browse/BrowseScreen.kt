package com.mcqapp.ui.browse

import android.content.ActivityNotFoundException
import android.content.Intent

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import com.mcqapp.domain.isFlashcard
import com.mcqapp.ui.BrowseViewModelFactory
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.common.UiMessageTone
import com.mcqapp.util.ContentElements
import com.mcqapp.util.parseNonNegativeDecimal
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    repository: McqRepository,
    paperId: String,
    focusQuestionId: String = "",
    initialCategoryIds: List<String> = emptyList(),
    navController: NavController,
    viewModel: BrowseViewModel = viewModel(
        key = "browse-$paperId",
        factory = BrowseViewModelFactory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
            paperId,
            initialCategoryIds
        )
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allPapers by viewModel.papers.collectAsStateWithLifecycle()
    // Collected here (not inside the Scaffold content) so the process-death
    // restore effect below can read and overwrite it before the list composes.
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    // rememberSaveable: a config change used to discard a multi-select, and
    // process death with the picker up made export silently no-op
    // (consolidated_bugs.md F10). Sets and enums are Serializable.
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    // One confirmation for DELETE, shared by the row action (one id) and the
    // bulk action (many). Null = no dialog. Saveable so a rotation while the
    // dialog is up does not silently drop the choice (B1).
    var pendingDeleteIds by rememberSaveable { mutableStateOf<Set<String>?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    // Saveable: a rotation mid-render must not close the dialog while the VM
    // keeps rendering in its own scope (B3).
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    // The id set is captured at pick time: CreateDocument hands back a Uri
    // later, by which point selection mode may already be over.
    var exportIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var exportFormat by rememberSaveable {
        mutableStateOf(com.mcqapp.data.export.ExportFormat.JSON_INLINE)
    }
    // Saved filter chips, restored into the VM on process death (B7). Null
    // means "nothing saved yet", so the route-seeded category filter wins.
    var savedFilters by rememberSaveable { mutableStateOf<BrowseFilterSnapshot?>(null) }
    // Plain remember, NOT saveable: this is a per-composition one-shot guard.
    // Saving it made a process-death recreation skip the restore and then let
    // the sync effect overwrite the saved chips with the fresh VM's defaults.
    var filtersRestored by remember { mutableStateOf(false) }
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    val platformContext = androidx.compose.ui.platform.LocalContext.current
    // LocalResources (not LocalContext.current) keeps the chooser title
    // configuration-aware: LocalContext.getString() is not re-read when the
    // Configuration changes (LocalContextGetResourceValueCall).
    val platformResources = LocalResources.current
    val sendMessage = LocalUiMessageSender.current
    // Resolved in composition so the action callbacks stay plain lambdas.
    val shareNoAppText = stringResource(R.string.share_no_app)
    val duplicatedText = stringResource(R.string.question_duplicated)
    val selectedLabel = stringResource(R.string.r2_browse_selected)
    // Count label resolved here (not inside exportTitle) so the plain callback
    // stays non-composable; it recomposes when the captured id set changes.
    val exportCountText = pluralStringResource(
        R.plurals.r2_browse_question_count, exportIds.size, exportIds.size
    )
    val moveCollisionToast = stringResource(R.string.r2_browse_move_collision_toast)

    val error by viewModel.error.collectAsStateWithLifecycle()
    val moveAffectedCount by viewModel.moveAffectedCount.collectAsStateWithLifecycle()

    // Shared by the export dialog's own finish and the one-shot share-result
    // collector, so closing on success is always done in one place (B3).
    fun finishSelectionExport() {
        showExportDialog = false
        selectionMode = false
        selectedIds = emptySet()
    }

    // A rotation keeps the VM, so its filter state is current; only a process
    // death recreates it empty. Restore the saved chips once per composition,
    // then keep the snapshot in sync with the VM from then on (B7). Only fields
    // that actually differ are written: a rotation replays this effect against
    // a VM that already holds the filters, and writing equal values would
    // rebuild the pager for no reason.
    LaunchedEffect(Unit) {
        if (filtersRestored) return@LaunchedEffect
        val plan = planBrowseFilterRestore(savedFilters, filterState)
        if (plan.query != null) viewModel.updateQuery(plan.query)
        if (plan.filter != null) viewModel.updateFilter(plan.filter)
        if (plan.categoryFilterIds != null) {
            viewModel.updateCategoryFilters(plan.categoryFilterIds)
        }
        filtersRestored = true
    }
    LaunchedEffect(filterState.query, filterState.filter, filterState.categoryFilterIds) {
        if (filtersRestored) savedFilters = BrowseFilterSnapshot.of(filterState)
    }

    // One-shot handoff from the VM's share render. The render runs in the VM's
    // scope, so leaving composition can no longer cancel it; we only start the
    // chooser once it is ready (B3). A replayed StateFlow (not a plain
    // SharedFlow) covers the recreation gap: an event emitted before this
    // screen exists is still seen, and consume(id) stops a second showing.
    val shareEvent by viewModel.shareEvents.latest.collectAsStateWithLifecycle()
    LaunchedEffect(shareEvent) {
        val event = shareEvent ?: return@LaunchedEffect
        finishSelectionExport()
        val intent = event.value
        if (intent != null) {
            try {
                platformContext.startActivity(
                    Intent.createChooser(
                        intent,
                        platformResources.getString(R.string.share_chooser_title)
                    )
                )
            } catch (_: ActivityNotFoundException) {
                sendMessage(UiMessage(shareNoAppText, UiMessageTone.Error))
            }
        }
        viewModel.shareEvents.consume(event.id)
    }

    fun exportTitle(): String {
        val paper = state.paper?.title ?: selectedLabel
        return "$paper $exportCountText"
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
                        if (selectionMode) stringResource(R.string.n_selected, selectedIds.size)
                        else state.paper?.title ?: stringResource(R.string.browse_title)
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
                        // Select all in the current queue. The button was
                        // asked for as an icon, alongside the selection
                        // actions, and it resolves the live filter so a
                        // selection never includes rows outside the list.
                        IconButton(
                            onClick = {
                                viewModel.selectAllInScope { ids -> selectedIds = ids }
                            },
                            modifier = Modifier.testTag("browse-select-all")
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.select_all)
                            )
                        }
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
                            onClick = {
                                // Resolve passage siblings before the dialog
                                // opens, so it can count what will move
                                // (question_actions_audit.md QA-02). Clear the
                                // previous scope first: a stale count must not
                                // describe a different selection.
                                viewModel.clearMoveScope()
                                viewModel.prepareMove(selectedIds)
                                showMoveDialog = true
                            },
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
                            onClick = {
                                pendingDeleteIds = BrowseDeleteConfirmation.request(
                                    pendingDeleteIds, selectedIds
                                )
                            },
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

        // The field shows what was typed; the list's query follows 300 ms later.
        var searchInput by remember { mutableStateOf("") }
        // Whether the field holds the user's focus: the sync-back below must
        // not touch it while they are typing.
        var searchFocused by remember { mutableStateOf(false) }
        LaunchedEffect(searchInput) {
            if (searchInput != filterState.query) {
                delay(300)
                viewModel.updateQuery(searchInput)
            }
        }
        LaunchedEffect(filterState.query) {
            // A keystroke landing in the frame after a debounced commit used
            // to be overwritten by this sync-back, reverting the field
            // (consolidated_bugs.md B4). Sync only an unfocused field — a
            // focused one is ahead of the committed query by design.
            if (filterState.query != searchInput && !searchFocused) {
                searchInput = filterState.query
            }
        }
        // A filter change re-scopes the list: a selection made under the old
        // scope would let bulk Delete/Edit act on rows now hidden — the chips
        // stay tappable in selection mode (consolidated_bugs.md F7). The
        // selection is dropped wholesale; pruning to the visible window cannot
        // work, because paging hides rows beyond the loaded pages too.
        LaunchedEffect(filterState.query, filterState.categoryFilterIds, filterState.filter) {
            if (selectionMode) {
                selectionMode = false
                selectedIds = emptySet()
            }
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
        val categoryFilterIds = filterState.categoryFilterIds
        // One applied category names itself; several say how many; none says
        // "All categories". A stale single id falls back to the same word (its
        // scope is unfiltered, see BrowseViewModel.subtreeIds).
        val selectedCategoryLabel = when {
            categoryFilterIds.isEmpty() -> stringResource(R.string.all_categories)
            categoryFilterIds.size == 1 -> categoryFilterIds.first().let { id ->
                flatCategories.firstOrNull { it.first.id == id }?.first?.title
            } ?: stringResource(R.string.all_categories)
            else -> pluralStringResource(R.plurals.study_scope_many, categoryFilterIds.size, categoryFilterIds.size)
        }

        // Delayed spinner: fast loads show blank instead of flashing text.
        val refreshing = items.loadState.refresh is LoadState.Loading
        val showLoading = com.mcqapp.util.rememberDelayedVisibility(refreshing && items.itemCount == 0)
        // The whole-screen early return is the FIRST load's business only:
        // the same condition is true during every filter-change refresh, and
        // returning here unmounted the search field, the chips and the
        // dialogs for as long as a big paper took to refresh
        // (consolidated_bugs.md K1).
        var firstLoadDone by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(items.loadState.refresh) {
            if (items.loadState.refresh is LoadState.NotLoading) firstLoadDone = true
        }
        if (showLoading && !firstLoadDone) {
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
            filterState.query.isBlank() && categoryFilterIds.isEmpty()
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
        // the row only because PagingConfig.jumpThreshold is set (jumps are off
        // by default), and reloads the page around it instead of walking there.
        //
        // The scroll is one-shot per focus id: every filter chip and every
        // write elsewhere rebuilds the pager, flipping itemCount 0→>0 again,
        // and the effect used to yank the list back to the focused row each
        // time (consolidated_bugs.md F5). The flag is set only once the
        // anchor has actually resolved, so a first pass that runs before the
        // list has rows still gets its scroll.
        var scrolledTo by rememberSaveable(focusQuestionId) { mutableStateOf(false) }
        val listHasRows = items.itemCount > 0
        LaunchedEffect(focusQuestionId, listHasRows) {
            if (focusQuestionId.isBlank() || !listHasRows || scrolledTo) return@LaunchedEffect
            viewModel.browseIndexOf(focusQuestionId) { index ->
                // +1 for the header item above the cards.
                if (index >= 0) {
                    listState.scrollToItem(index + 1)
                    scrolledTo = true
                }
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
            // A later refresh keeps the controls composed and notes the load
            // above the list instead of replacing the screen (K1).
            if (showLoading) {
                item(key = "browse-loading", contentType = "loading") {
                    Text(
                        stringResource(R.string.loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { searchFocused = it.isFocused }
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        stringResource(R.string.r2_browse_filter_all) to BrowseListFilter.ALL,
                        stringResource(R.string.r2_browse_filter_no_answer) to BrowseListFilter.NO_ANSWER,
                        stringResource(R.string.r2_browse_filter_no_explanation) to BrowseListFilter.NO_EXPLANATION,
                        null to BrowseListFilter.UNCATEGORIZED
                    ).forEach { (label, kind) ->
                        FilterChip(
                            selected = filterState.filter == kind,
                            onClick = { viewModel.updateFilter(kind) },
                            label = {
                                // The one resource label is resolved inside the
                                // Text that renders it; the rest stay literals.
                                Text(
                                    label ?: stringResource(R.string.uncategorized),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
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
                        value = selectedCategoryLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.category)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            // Tagged: the field shows a category title, and a
                            // scoped list renders that same title as a row
                            // label, so text alone is not unique.
                            .testTag("browse-category-filter")
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
                        filterState.query.isBlank() && categoryFilterIds.isEmpty()
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
                        // B1: a single row delete asks for the same confirmation
                        // as the bulk action, with a count of one. No one-tap
                        // destructive action.
                        onDelete = {
                            pendingDeleteIds = BrowseDeleteConfirmation.request(
                                pendingDeleteIds, setOf(question.id)
                            )
                        },
                        onDuplicate = {
                            viewModel.duplicateQuestion(question.id) { duplicated ->
                                if (duplicated) {
                                    sendMessage(UiMessage(duplicatedText, UiMessageTone.Success))
                                }
                            }
                        }
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
            val marksError = bulkMarks.trim().isNotEmpty() &&
                parseNonNegativeDecimal(bulkMarks) == null
            AlertDialog(
                onDismissRequest = { showBulkDialog = false },
                title = { Text(pluralStringResource(R.plurals.edit_n_questions, selectedIds.size, selectedIds.size)) },
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
                            isError = marksError,
                            supportingText = if (marksError) {
                                { Text(stringResource(R.string.marks_must_be_non_negative)) }
                            } else {
                                null
                            },
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
                    TextButton(
                        onClick = {
                            viewModel.bulkEdit(selectedIds, bulkMarks, bulkDifficulty, bulkTags)
                            selectedIds = emptySet()
                            selectionMode = false
                            showBulkDialog = false
                        },
                        enabled = !marksError
                    ) { Text(stringResource(R.string.apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        // One confirmation for both entry points. The count comes from the
        // captured pending set, NOT from the live selection: the row action
        // deletes one question while a selection may still be ticked (B1).
        val pendingDelete = pendingDeleteIds
        if (BrowseDeleteConfirmation.showing(pendingDelete)) {
            val deleteCount = BrowseDeleteConfirmation.count(pendingDelete)
            AlertDialog(
                onDismissRequest = { pendingDeleteIds = null },
                title = { Text(pluralStringResource(R.plurals.delete_n_questions, deleteCount, deleteCount)) },
                text = { Text(stringResource(R.string.this_cannot_be_undone)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteQuestions(pendingDelete.orEmpty())
                        selectedIds = emptySet()
                        selectionMode = false
                        pendingDeleteIds = null
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteIds = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        // One error channel: a bulk edit, a question action (including Select
        // all) and an export can each fail, but only one dialog is ever shown.
        error?.let { shown ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissError() },
                title = { Text(stringResource(shown.titleRes)) },
                text = { Text(shown.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }

        if (showExportDialog) {
            fun saveSelectionToFile() {
                showExportDialog = false
                exportSaver(exportFormat).launch(
                    com.mcqapp.data.export.PaperExporter.fileNameFor(
                        exportTitle(), exportFormat
                    )
                )
                // Selection mode ends with the export: the ids are now held by
                // exportIds, and the user asked for a file.
                selectionMode = false
                selectedIds = emptySet()
            }

            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text(pluralStringResource(R.plurals.export_n_selected, exportIds.size, exportIds.size)) },
                text = {
                    // All formats must be reachable on a phone: an unscrollable
                    // column leaves the lower ones off-screen and untappable.
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (isSharing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.sharing))
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        com.mcqapp.data.export.ExportFormat.entries.forEach { format ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("export-format-${format.name}")
                                    .clickable { exportFormat = format }
                                    .semantics { selected = exportFormat == format }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = exportFormat == format,
                                    onClick = { exportFormat = format }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(format.titleRes), style = MaterialTheme.typography.bodyMedium)
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
                        TextButton(enabled = !isSharing, onClick = { saveSelectionToFile() }) {
                            Text(stringResource(R.string.share_save))
                        }
                        TextButton(
                            enabled = !isSharing,
                            onClick = { viewModel.shareSelection(exportIds, exportTitle(), exportFormat) }
                        ) {
                            Text(stringResource(R.string.share_share))
                        }
                    }
                },
                dismissButton = {
                    TextButton(enabled = !isSharing, onClick = { showExportDialog = false }) {
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
                onDismissRequest = {
                    showMoveDialog = false
                    viewModel.clearMoveScope()
                },
                title = {
                    Text(pluralStringResource(
                        if (moveIsCopy) R.plurals.copy_n_questions_to else R.plurals.move_n_questions_to,
                        selectedIds.size, selectedIds.size
                    ))
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
                                value = movePaper?.title ?: stringResource(R.string.select_paper),
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
                        // Moving to another paper keeps each card's schedule
                        // and review history and carries them to the new
                        // paper's statistics, and a shared passage takes its
                        // siblings along (QA-02). Both are said before the
                        // tap, not discovered after it. B2: the collision
                        // exception is disclosed too — a destination card that
                        // already exists wins and the source history is dropped.
                        if (!moveIsCopy && movePaperId != paperId) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.move_cross_paper_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                stringResource(R.string.r2_browse_move_collision_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        val affected = moveAffectedCount
                        if (!moveIsCopy && affected != null && affected > selectedIds.size) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pluralStringResource(R.plurals.move_includes_passage, affected, affected),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val movedCount = selectedIds.size
                        targets.forEach { (node, depth) ->
                            TextButton(
                                onClick = {
                                    if (moveIsCopy) {
                                        viewModel.copyQuestions(selectedIds, node.id) { copied ->
                                            if (copied) {
                                                sendMessage(
                                                    UiMessage(
                                                        platformResources.getQuantityString(
                                                            R.plurals.questions_copied_count,
                                                            movedCount,
                                                            movedCount
                                                        ),
                                                        UiMessageTone.Success
                                                    )
                                                )
                                            }
                                        }
                                    } else {
                                        // The move reports the real scope it
                                        // touched (passage siblings included),
                                        // never a stale dialog count or just the
                                        // ticked rows.
                                        viewModel.moveQuestions(selectedIds, node.id) { total ->
                                            if (total != null) {
                                                // B2: a cross-paper move can drop
                                                // source history on a collision;
                                                // say so instead of implying a
                                                // clean carry-over.
                                                val moved = platformResources.getQuantityString(
                                                    R.plurals.questions_moved_count, total, total
                                                )
                                                val message = if (movePaperId != paperId) {
                                                    "$moved $moveCollisionToast"
                                                } else {
                                                    moved
                                                }
                                                sendMessage(UiMessage(message, UiMessageTone.Success))
                                            }
                                        }
                                    }
                                    selectedIds = emptySet()
                                    selectionMode = false
                                    showMoveDialog = false
                                    viewModel.clearMoveScope()
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
                    TextButton(onClick = {
                        showMoveDialog = false
                        viewModel.clearMoveScope()
                    }) { Text(stringResource(R.string.cancel)) }
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
                    stringResource(R.string.editor_index, index),
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
            // The category on the left, the card type on the right. A
            // flashcard's type is a rendered label, not a description only: it
            // tells the reader why there are no options below.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (categoryTitle != null) {
                    Text(
                        categoryTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (question.isFlashcard) {
                    Text(
                        stringResource(R.string.r2_flashcard_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    // B6: an unlabeled checkbox is announced as just
                    // "checkbox, checked". Name the question it selects.
                    // Resolved here, in composition: the semantics lambda is
                    // not a @Composable context.
                    val selectLabel = stringResource(R.string.r2_browse_select_question, index)
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.semantics { contentDescription = selectLabel }
                    )
                } else {
                    Text(
                        stringResource(R.string.editor_index, index),
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
                // B6: the glyph alone is meaningless to a screen reader.
                // Replace it with a spoken correct/incorrect label so the
                // answer is available without seeing the tick. Resolved in
                // composition; the semantics lambda is not @Composable.
                val correctnessLabel = stringResource(
                    if (isCorrect) R.string.r2_browse_correct_option
                    else R.string.r2_browse_other_option
                )
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        if (isCorrect) "✓" else "○",
                        color = if (isCorrect) verdictColors().correctBorder else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .width(20.dp)
                            .clearAndSetSemantics { contentDescription = correctnessLabel },
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
                        // A card's back is its answer, not an explanation: same
                        // distinction Study makes on the revealed card.
                        contentDescription = stringResource(
                            if (question.isFlashcard) R.string.r2_study_answer_heading
                            else R.string.explanation
                        ),
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
            QuestionImage(
                src = question.explanationImage,
                contentDescription = stringResource(
                    if (question.isFlashcard) R.string.r2_study_answer_image
                    else R.string.explanation_image
                )
            )

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

/**
 * B1 — the one Delete confirmation, shared by the row action (one id) and the
 * bulk action (many). Kept as a pure seam so the "never confirm an empty set,
 * count the captured scope, not the live selection" contract is testable
 * without a Compose test. [request] ignores an empty [ids], so a stale
 * selection can never put up a "Delete 0 questions?" dialog.
 */
internal object BrowseDeleteConfirmation {
    fun request(current: Set<String>?, ids: Set<String>): Set<String>? =
        if (ids.isEmpty()) current else ids

    fun count(pending: Set<String>?): Int = pending?.size ?: 0

    fun showing(pending: Set<String>?): Boolean = !pending.isNullOrEmpty()
}

/**
 * B7 — a serializable snapshot of the filter chips, saved from the screen and
 * restored into the freshly created VM after process death. Serializable so the
 * default `rememberSaveable` registry can store it in the saved-state Bundle.
 */
internal data class BrowseFilterSnapshot(
    val query: String,
    val filter: BrowseListFilter,
    val categoryFilterIds: Set<String>
) : java.io.Serializable {
    companion object {
        fun of(state: BrowseFilterState): BrowseFilterSnapshot = BrowseFilterSnapshot(
            state.query, state.filter, state.categoryFilterIds
        )
    }
}

/**
 * Saved chips win over the route-seeded defaults; with nothing saved the
 * route's initial (category) filter is kept. Pure so both branches are tested.
 */
internal fun restoreBrowseFilters(
    saved: BrowseFilterSnapshot?,
    initial: BrowseFilterState
): BrowseFilterState = saved?.let {
    BrowseFilterState(query = it.query, filter = it.filter, categoryFilterIds = it.categoryFilterIds)
} ?: initial

/**
 * Only the fields that differ from the live VM state. Process death starts
 * from the route seed and applies all saved chips; a rotation replays against
 * a VM that already holds them and applies nothing. A null field means "leave
 * this one alone" — writing equal values would rebuild the pager needlessly.
 */
internal data class BrowseFilterRestore(
    val query: String? = null,
    val filter: BrowseListFilter? = null,
    val categoryFilterIds: Set<String>? = null
) {
    val hasChanges: Boolean get() = query != null || filter != null || categoryFilterIds != null
}

internal fun planBrowseFilterRestore(
    saved: BrowseFilterSnapshot?,
    current: BrowseFilterState
): BrowseFilterRestore {
    if (saved == null) return BrowseFilterRestore()
    val restored = restoreBrowseFilters(saved, current)
    return BrowseFilterRestore(
        query = restored.query.takeIf { it != current.query },
        filter = restored.filter.takeIf { it != current.filter },
        categoryFilterIds = restored.categoryFilterIds.takeIf { it != current.categoryFilterIds }
    )
}
