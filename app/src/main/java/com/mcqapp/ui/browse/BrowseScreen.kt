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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.ui.BrowseViewModelFactory
import com.mcqapp.util.Logger
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

    fun exportSaver(format: com.mcqapp.data.export.ExportFormat) = when (format) {
        com.mcqapp.data.export.ExportFormat.JSON_INLINE -> exportJsonLauncher
        com.mcqapp.data.export.ExportFormat.ZIP -> exportZipLauncher
        com.mcqapp.data.export.ExportFormat.HTML, com.mcqapp.data.export.ExportFormat.HTML_QUIZ ->
            exportHtmlLauncher
        com.mcqapp.data.export.ExportFormat.PDF, com.mcqapp.data.export.ExportFormat.PDF_ANSWER_KEY ->
            exportPdfLauncher
        com.mcqapp.data.export.ExportFormat.APKG -> exportApkgLauncher
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
        // Delayed spinner: fast loads show blank instead of flashing text.
        val showLoading = com.mcqapp.util.rememberDelayedVisibility(state.loading)
        if (showLoading) {
            Text(
                stringResource(R.string.loading),
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
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

        if (state.questions.isEmpty()) {
            Text(
                stringResource(R.string.no_questions_in_this_paper),
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        // Hoisted + memoized: cards used to render inside a single lazy item
        // via forEach, defeating virtualization and OOMing big papers.
        val filter = remember { mutableStateOf("All") }
        val questions = state.questions
        val filterValue = filter.value
        var searchQuery by remember { mutableStateOf("") }
        // Debounced like the global search: filtering lowercases every
        // question's text, tags and option texts, and this list is already
        // in memory with base64 images, so it ran a full linear scan per
        // keystroke on the main thread.
        var searchInput by remember { mutableStateOf("") }
        LaunchedEffect(searchInput) {
            if (searchInput != searchQuery) {
                delay(300)
                searchQuery = searchInput
            }
        }
        val searched = remember(questions, searchQuery) {
            com.mcqapp.domain.QuestionSearch.filter(questions, searchQuery)
        }
        val filtered = remember(searched, filterValue, state.paper) {
            val titles = flattenCategories(state.paper?.categories ?: emptyList())
                .associate { it.first.id to it.first.title }
            when (filterValue) {
                "No answer" -> searched.filter { it.correctOptionIds.isEmpty() }
                "No explanation" -> searched.filter { it.explanation.isBlank() }
                "Uncategorized" -> com.mcqapp.domain.QuestionSearch
                    .filterUncategorized(searched, titles)
                else -> searched
            }
        }
        val filteredIds = remember(filtered) { filtered.map { it.id } }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // Deep link from global search: highlight persists on screen.
        var highlightId by remember(focusQuestionId) { mutableStateOf(focusQuestionId) }
        androidx.compose.runtime.LaunchedEffect(focusQuestionId, filtered) {
            if (focusQuestionId.isBlank() || filtered.isEmpty()) return@LaunchedEffect
            val index = filtered.indexOfFirst { it.id == focusQuestionId }
            // +1 for the header item above the cards.
            if (index >= 0) listState.scrollToItem(index + 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
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
                    listOf("All", "No answer", "No explanation", "Uncategorized").forEach { label ->
                        FilterChip(
                            selected = filter.value == label,
                            onClick = { filter.value = label },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.no_questions_match_this_filter),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            val reorderEnabled = filterValue == "All" && searchQuery.isBlank()
            itemsIndexed(filtered, key = { _, question -> question.id }) { index, question ->
                BrowseQuestionCard(
                    index = index + 1,
                    question = question,
                    selectionMode = selectionMode,
                    selected = question.id in selectedIds,
                    highlighted = question.id == highlightId,
                    canMoveUp = reorderEnabled &&
                        com.mcqapp.domain.Reorder.canMove(filtered, index, -1),
                    canMoveDown = reorderEnabled &&
                        com.mcqapp.domain.Reorder.canMove(filtered, index, +1),
                    onMoveUp = {
                        viewModel.swapQuestions(question.id, filtered[index - 1].id)
                    },
                    onMoveDown = {
                        viewModel.swapQuestions(question.id, filtered[index + 1].id)
                    },
                    onToggleSelect = {
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(question.id)) remove(question.id)
                        }
                    },
                    onEdit = {
                        com.mcqapp.ui.editor.EditorSession.start(
                            ids = filteredIds,
                            index = index
                        )
                        navController.navigate(
                            com.mcqapp.ui.navigation.editorRoute(question.id, paperId, question.categoryId)
                        )
                    },
                    onDelete = { viewModel.deleteQuestion(question.id) },
                    onDuplicate = { viewModel.duplicateQuestion(question.id) }
                )
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
                flattenCategories(movePaper?.categories ?: emptyList())
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

@Composable
private fun BrowseQuestionCard(
    index: Int,
    question: com.mcqapp.domain.Question,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
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
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
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

private fun flattenCategories(
    nodes: List<com.mcqapp.domain.CategoryNode>,
    depth: Int = 0
): List<Pair<com.mcqapp.domain.CategoryNode, Int>> = buildList {
    for (node in nodes) {
        add(node to depth)
        addAll(flattenCategories(node.children, depth + 1))
    }
}
